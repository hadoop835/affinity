/*
 * Copyright 2016 Michal Harish, michal.harish@gmail.com
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

package io.amient.affinity.spark

import io.amient.affinity.kafka.KafkaBroker
import org.apache.spark.Partition

/**
  * KafkaSplit is always related to a single kafka topic. Kafka topic may have multiple partitions and these
  * can be further sub-divided multiple times to get extra parallelism. Hence the number of splits if defined by
  * number of kafka partition times the extraParallelism factor.
  *
  * @param rddId
  * @param index       ∈ { i | 0 ≤ i < numPartitions * extraParallelism }
  * @param partition   - kafka partition number
  * @param startOffset - offset range start - inclusive
  * @param stopOffset  - offset range end - exclusive
  * @param leader      - host:port of the leader broker for this partition
  */
class KafkaSplit(rddId: Int, override val index: Int,
                 val partition: Int, val startOffset: Long, val stopOffset: Long,
                 val leader: Option[KafkaBroker]) extends Partition {
  override def hashCode: Int = 41 * 41 * (41 + rddId) + (41 * index)

  override def toString = s"KafkaSplit(index=$index, partition=$partition [$startOffset:$stopOffset], rddId=$rddId)"
}