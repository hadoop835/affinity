/*
 * Copyright 2016-2018 Michal Harish, michal.harish@gmail.com
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.amient.affinity.kafka

import java.util.Properties
import java.util.concurrent.Future
import java.{lang, util}

import com.typesafe.config.Config
import io.amient.affinity.core.config.{Cfg, CfgStruct}
import io.amient.affinity.core.storage._
import io.amient.affinity.core.util.{EventTime, JavaPromise, MappedJavaFuture, TimeRange}
import io.amient.affinity.kafka.KafkaStorage.KafkaStorageConf
import org.apache.kafka.clients.consumer.{ConsumerRebalanceListener, KafkaConsumer, OffsetAndMetadata, OffsetCommitCallback}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, ByteArraySerializer}
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.language.reflectiveCalls

object KafkaStorage {

  object StateConf extends KafkaStateConf {
    override def apply(config: Config): KafkaStateConf = new KafkaStateConf().apply(config)
  }

  class KafkaStateConf extends CfgStruct[KafkaStateConf](classOf[StateConf]) {
    val Storage = struct("storage", new KafkaStorageConf)
  }

  object KafkaStorageConf extends KafkaStorageConf {
    override def apply(config: Config): KafkaStorageConf = new KafkaStorageConf().apply(config)
  }

  class KafkaStorageConf extends CfgStruct[KafkaStorageConf](classOf[LogStorageConf]) {
    val Topic = string("kafka.topic", true)
    val ReplicationFactor = integer("kafka.replication.factor", 1)
    val BootstrapServers = string("kafka.bootstrap.servers", true)
    val Producer = struct("kafka.producer", new KafkaProducerConf)
    val Consumer = struct("kafka.consumer", new KafkaConsumerConf)
  }

  class KafkaProducerConf extends CfgStruct[KafkaProducerConf](Cfg.Options.IGNORE_UNKNOWN)

  class KafkaConsumerConf extends CfgStruct[KafkaConsumerConf](Cfg.Options.IGNORE_UNKNOWN) {
    val GroupId = string("group.id", false)
  }

}

class KafkaLogStorage(conf: LogStorageConf) extends LogStorage[java.lang.Long] with ConsumerRebalanceListener {

  private val log = LoggerFactory.getLogger(classOf[KafkaLogStorage])

  val kafkaStorageConf = KafkaStorageConf(conf)

  val topic = kafkaStorageConf.Topic()
  val keySubject: String = s"${topic}-key"
  val valueSubject: String = s"${topic}-value"

  private val producerConfig = new Properties() {
    if (kafkaStorageConf.Producer.isDefined) {
      val producerConfig = kafkaStorageConf.Producer.config()
      if (producerConfig.hasPath("bootstrap.servers")) throw new IllegalArgumentException("bootstrap.servers cannot be overriden for KafkaStroage producer")
      if (producerConfig.hasPath("key.serializer")) throw new IllegalArgumentException("Binary kafka stream cannot use custom key.serializer")
      if (producerConfig.hasPath("value.serializer")) throw new IllegalArgumentException("Binary kafka stream cannot use custom value.serializer")
      producerConfig.entrySet().foreach { case (entry) =>
        put(entry.getKey, entry.getValue.unwrapped())
      }
    }
    put("bootstrap.servers", kafkaStorageConf.BootstrapServers())
    put("value.serializer", classOf[ByteArraySerializer].getName)
    put("key.serializer", classOf[ByteArraySerializer].getName)
  }

  private val consumerProps = new Properties() {
    put("auto.offset.reset", "earliest")
    if (kafkaStorageConf.Consumer.isDefined) {
      val consumerConfig = kafkaStorageConf.Consumer.config()
      if (consumerConfig.hasPath("bootstrap.servers")) throw new IllegalArgumentException("bootstrap.servers cannot be overriden for KafkaStroage consumer")
      if (consumerConfig.hasPath("enable.auto.commit")) throw new IllegalArgumentException("enable.auto.commit cannot be overriden for KafkaStroage consumer")
      if (consumerConfig.hasPath("key.deserializer")) throw new IllegalArgumentException("key.deserializer cannot be overriden for KafkaStroage consumer")
      if (consumerConfig.hasPath("value.deserializer")) throw new IllegalArgumentException("value.deserializer cannot be overriden for KafkaStroage consumer")
      consumerConfig.entrySet().foreach { case (entry) =>
        put(entry.getKey, entry.getValue.unwrapped())
      }
    }
    put("bootstrap.servers", kafkaStorageConf.BootstrapServers())
    put("enable.auto.commit", "false")
    put("key.deserializer", classOf[ByteArrayDeserializer].getName)
    put("value.deserializer", classOf[ByteArrayDeserializer].getName)
  }

  private val kafkaConsumer = new KafkaConsumer[Array[Byte], Array[Byte]](consumerProps)
  private val partitionProgress = new mutable.HashMap[Int, java.lang.Long]()
  private var closed = false
  private var range: TimeRange = TimeRange.UNBOUNDED

  override def getNumPartitions(): Int = {
    kafkaConsumer.partitionsFor(topic).size()
  }

  override def reset(range: TimeRange): Unit = {
    this.range = range
    kafkaConsumer.subscribe(List(topic), this)
  }

  override def reset(partition: Int, range: TimeRange): Unit = {
    val tp = new TopicPartition(topic, partition)
    this.range = range
    kafkaConsumer.assign(List(tp))
    onPartitionsAssigned(List(tp))
  }

  override def reset(partition: Int, startPosition: java.lang.Long): java.lang.Long = {
    val tp = new TopicPartition(topic, partition)
    val startOffset = if (startPosition == null || startPosition <0) kafkaConsumer.beginningOffsets(List(tp))(tp) else startPosition
    if (startOffset < 0) {
      return null
    } else {
      kafkaConsumer.seek(tp, startOffset)
      val maxOffset: Long = kafkaConsumer.endOffsets(List(tp))(tp)
      // exclusive of the time range end
      val stopOffset: Long = Option(kafkaConsumer.offsetsForTimes(Map(tp -> new java.lang.Long(range.end))).get(tp)).map(_.offset).getOrElse(maxOffset) - 1
      if (stopOffset >= startOffset) {
        partitionProgress.put(tp.partition, stopOffset)
      } else {
        partitionProgress.remove(tp.partition)
      }
      stopOffset
    }
  }

  override def onPartitionsRevoked(partitions: util.Collection[TopicPartition]) = {
    partitions.foreach(tp => partitionProgress.remove(tp.partition))
  }

  override def onPartitionsAssigned(partitions: util.Collection[TopicPartition]) = {
    partitions.foreach {
      tp =>
        val beginOffset: Long = kafkaConsumer.beginningOffsets(List(tp))(tp)
        val nextOffset: Long = Option(kafkaConsumer.committed(tp)).map(_.offset() + 1).getOrElse(0)
        val minOffset: Long = math.max(nextOffset, beginOffset)
        val startOffset: Long = Option(kafkaConsumer.offsetsForTimes(Map(tp -> new java.lang.Long(range.start))).get(tp)).map(_.offset).getOrElse(minOffset)
        log.debug(s"Reset partition=${tp.partition()} time range ${range.getLocalStart}:${range.getLocalEnd}")
        reset(tp.partition, startOffset)
    }
  }

  override def fetch(unbounded: Boolean): util.Iterator[LogEntry[java.lang.Long]] = {

    if (!unbounded && partitionProgress.isEmpty) {
      return null
    }

    val kafkaRecords = try {
      kafkaConsumer.poll(500)
    } catch {
      case _: WakeupException => return null
    }

    kafkaRecords.iterator.filter { record =>
      if (unbounded) {
        record.timestamp >= range.start && record.timestamp <= range.end
      } else if (!partitionProgress.contains(record.partition)) {
        false
      } else {
        if (record.offset >= partitionProgress(record.partition)) {
          partitionProgress.remove(record.partition)
        }
        val valid = record.timestamp >= range.start && record.timestamp <= range.end
        valid
      }
    }.map {
      case r => new LogEntry(new java.lang.Long(r.offset), r.key, r.value, r.timestamp)
    }
  }

  def cancel(): Unit = kafkaConsumer.wakeup()

  def commit(): JavaPromise[lang.Long] = {
    val promise = new JavaPromise[java.lang.Long]
    kafkaConsumer.commitAsync(new OffsetCommitCallback {
      def onComplete(offsets: util.Map[TopicPartition, OffsetAndMetadata], exception: Exception) = {
        if (exception != null) promise.failure(exception) else promise.success(System.currentTimeMillis())
      }
    })
    promise
  }

  private var producerActive = false

  lazy protected val producer = new KafkaProducer[Array[Byte], Array[Byte]](producerConfig)

  override def append(record: Record[Array[Byte], Array[Byte]]): java.util.concurrent.Future[java.lang.Long] = {
    producerActive = true
    val producerRecord: ProducerRecord[Array[Byte], Array[Byte]] = if (record.key == null) {
      new ProducerRecord(topic, null, record.timestamp, null, record.value)
    } else {
      new ProducerRecord(topic, null, record.timestamp, record.key, record.value)
    }
    new MappedJavaFuture[RecordMetadata, java.lang.Long](producer.send(producerRecord)) {
      override def map(result: RecordMetadata): java.lang.Long = result.offset()
    }
  }

  override def delete(key: Array[Byte]): Future[java.lang.Long] = {
    //kafka uses null value as a delete tombstone
    append(new Record[Array[Byte], Array[Byte]](key, null, EventTime.unix));
  }

  override def flush() = if (producerActive) {
    producer.flush()
  }

  override def close(): Unit = {
    try kafkaConsumer.close() finally try if (producerActive) producer.close() finally {
      closed = true
    }
  }

  override def isTombstone(entry: LogEntry[lang.Long]) = entry.value == null

  override def ensureCorrectConfiguration(ttlMs: Long, numPartitions: Int, readonly: Boolean) {
    log.warn(s"Using Kafka version < 0.11 - cannot auto-configure topics")
  }

}

