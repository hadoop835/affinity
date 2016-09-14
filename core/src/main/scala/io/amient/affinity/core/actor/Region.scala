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

package io.amient.affinity.core.actor

import java.util.Properties

import akka.actor.{Props, Status}
import akka.event.Logging
import io.amient.affinity.core.ack._
import io.amient.affinity.core.actor.Service.{BecomeMaster, BecomeStandby}
import io.amient.affinity.core.cluster.Coordinator
import io.amient.affinity.core.cluster.Coordinator.{AddMaster, RemoveMaster}

object Region {
  final val CONFIG_PARTITION_LIST = "partition.list"
}

class Region(appConfig: Properties, coordinator: Coordinator, partitionProps: Props)
  extends Container(appConfig: Properties, coordinator: Coordinator, "regions") {

  override val log = Logging.getLogger(context.system, this)

  import Region._

  val partitions = appConfig.getProperty(CONFIG_PARTITION_LIST).split("\\,").map(_.toInt).toList

  override def preStart(): Unit = {
    log.info("Starting Region")
    coordinator.watch(self)
    for (partition <- partitions) {
      /**
        * partition actor name is the physical partition id which is relied upon by DeterministicRoutingLogic
        * as well as Partition
        */
      context.actorOf(partitionProps, name = partition.toString )
    }
    super.preStart()
  }

  override def postStop(): Unit = {
    coordinator.unwatch(self)
    super.postStop()
  }

  import context.dispatcher

  override def receive: Receive = super.receive orElse {

    //TODO create coordinator watchLocal instead of this check
    case AddMaster(group, service) if (service.path.address.hasLocalScope) => ack(service, BecomeMaster(), sender)
    case AddMaster(group, ref) => sender ! Status.Success()
    case RemoveMaster(group, service) if (service.path.address.hasLocalScope) => ack(service, BecomeStandby(), sender)
    case RemoveMaster(group, ref) => sender ! Status.Success()
  }

}
