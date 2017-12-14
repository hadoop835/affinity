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

package io.amient.affinity.core.cluster


import akka.actor.{ActorSystem, Props}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigException}
import io.amient.affinity.avro.AvroSerde.AvroConf
import io.amient.affinity.core.ack
import io.amient.affinity.core.actor.Controller._
import io.amient.affinity.core.actor._
import io.amient.affinity.core.config._
import io.amient.affinity.core.storage.StateConf

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.{implicitConversions, postfixOps}
import scala.reflect.ClassTag
import scala.util.control.NonFatal

object Node {

  class Config extends CfgStruct[Config](Cfg.Options.IGNORE_UNKNOWN) {
    val Akka = struct("akka", new AkkaConfig, false)
    val Affi = struct("affinity", new AffinityConfig, true)
  }

  class AkkaConfig extends CfgStruct[AkkaConfig](Cfg.Options.IGNORE_UNKNOWN) {
    val Hostname = string("remote.netty.tcp.hostname", true)
    val Port = integer("remote.netty.tcp.port", true)
  }


  class AffinityConfig extends CfgStruct[AffinityConfig] {
    val Avro = struct("avro", new AvroConf(), true)
    val Coorinator = struct("coordinator", new Coordinator.CoorinatorConf, true)
    val State = group("state", classOf[StateConf], true)
    val Services = group("service", new ServicesApi.ServicesConf, false)
    val Containers = group("node.container", classOf[CfgIntList], false)
    val Gateway = struct("node.gateway", new ServicesApi.GatewayConf, false)
    val StartupTimeoutMs = longint("node.startup.timeout.ms", true)
    val ShutdownTimeoutMs = longint("node.shutdown.timeout.ms", true)
    val DataDir = filepath("node.data.dir", true)
    val SystemName = string("node.name", true)
    val Streams = group("node.stream", classOf[InputStreamConf], false)
  }

  class InputStreamConf extends CfgStruct[InputStreamConf](Cfg.Options.IGNORE_UNKNOWN)

}

class Node(config: Config) {

  val conf = new Node.Config()(config)
  private val actorSystemName: String = conf.Affi.SystemName()
  val startupTimeout = conf.Affi.StartupTimeoutMs().toLong milliseconds
  val shutdownTimeout = conf.Affi.ShutdownTimeoutMs().toLong milliseconds

  implicit val system = ActorSystem.create(actorSystemName, config)

  private val controller = system.actorOf(Props(new Controller), name = "controller")

  sys.addShutdownHook {
    //in case the process is stopped from outside
    shutdown()
  }

  final def shutdown(): Unit = {
    controller ! GracefulShutdown()
    Await.ready(system.whenTerminated, shutdownTimeout)
  }

  import system.dispatcher

  implicit val scheduler = system.scheduler

  implicit def partitionCreatorToProps[T <: Partition](creator: => T)(implicit tag: ClassTag[T]): Props = {
    Props(creator)
  }

  def start() = {
    conf.Affi.Containers().foreach {
      case (group: String, value: CfgIntList) =>
        val partitions = value().map(_.toInt).toList
        startContainer(group, partitions)
    }
    if (conf.Affi.Gateway.Class.isDefined) {
      //the gateway could be started programatically but in this case it is by config
      startGateway(conf.Affi.Gateway.Class().newInstance())
    }
  }

  def startContainer(group: String, partitions: List[Int]): Future[Unit] = {
    try {
      val serviceClass = conf.Affi.Services(group).PartitionClass()
      implicit val timeout = Timeout(startupTimeout)
      startupFutureWithShutdownFuse(controller ack CreateContainer(group, partitions, Props(serviceClass.newInstance())))
    } catch {
      case e: ConfigException =>
        throw new IllegalArgumentException(s"Could not start container for service $group with partitions ${partitions.mkString(", ")}", e)
    }
  }

  def startContainer[T <: Partition](group: String, partitions: List[Int], partitionCreator: => T)
                                    (implicit tag: ClassTag[T]): Future[Unit] = {
    implicit val timeout = Timeout(startupTimeout)
    startupFutureWithShutdownFuse(controller ack CreateContainer(group, partitions, Props(partitionCreator)))
  }

  /**
    * @param creator
    * @param tag
    * @tparam T
    * @return the httpPort on which the gateway is listening
    */
  def startGateway[T <: ServicesApi](creator: => T)(implicit tag: ClassTag[T]): Future[Int] = {
    implicit val timeout = Timeout(startupTimeout)
    startupFutureWithShutdownFuse {
      try {
        controller ack CreateGateway(Props(creator))
      } catch {
        case NonFatal(e) => e.printStackTrace(); throw e;
      }
    }
  }

  private def startupFutureWithShutdownFuse[T](eventual: Future[T]): Future[T] = {
    eventual onFailure {
      case NonFatal(e) =>
        e.printStackTrace()
        shutdown()
    }
    eventual
  }


}
