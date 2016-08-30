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

import akka.actor.{Actor, Props, Terminated}
import akka.event.Logging
import io.amient.affinity.core.cluster.Coordinator
import io.amient.affinity.core.{HttpRequestMapper, HttpInterface}

object Controller {

  final case class CreateRegion(handlerProps: Props)

  final case class CreateGateway(rootHandlerClass: Class[_ <: HttpRequestMapper])

  final case class GatewayCreated()

}

class Controller(appConfig: Properties) extends Actor {

  import Controller._

  implicit val system = context.system

  val log = Logging.getLogger(context.system, this)

  //controller terminates the system so cannot use system.dispatcher for Futures execution
  import scala.concurrent.ExecutionContext.Implicits.global

  val coordinator = try {
    Coordinator.fromProperties(appConfig)
  } catch {
    case e: Throwable =>
      system.terminate() onComplete { _ =>
        e.printStackTrace()
        System.exit(10)
      }
      throw e
  }

  val httpInterface: Option[HttpInterface] = try {
    HttpInterface.fromConfig(appConfig)
  } catch {
    case e: Throwable =>
      system.terminate() onComplete { _ =>
        e.printStackTrace()
        System.exit(10)
      }
      throw e
  }

  override def receive: Receive = {

    case CreateRegion(handlerProps) =>
      try {
        context.actorOf(Props(new Region(appConfig, coordinator, handlerProps)), name = "region")
      } catch {
        case e: Throwable =>
          system.terminate() onComplete { _ =>
            e.printStackTrace()
            System.exit(11)
          }
      }

    case CreateGateway(handlerClass) =>
      try {
        context.watch(context.actorOf(Props(new Gateway(appConfig, handlerClass)), name = "gateway"))
      } catch {
        case e: Throwable =>
          system.terminate() onComplete { _ =>
            e.printStackTrace()
            System.exit(12)
          }
      }

    case GatewayCreated() =>
      try {
        coordinator.watchRoutees(context.system, sender)
        httpInterface.foreach(_.bind(sender))

      } catch {
        case e: Throwable =>
          system.terminate() onComplete { _ =>
            e.printStackTrace()
            System.exit(13)
          }
      }

    case Terminated(gateway) =>
      log.info("Terminated(gateway) shutting down http interface")
      try {
        httpInterface.foreach(_.close)
      } finally {
        system.terminate() onComplete { _ =>
          //TODO there still could be an error that caused the gateway to terminate
          System.exit(0)
        }
      }

    case any => system.terminate() onComplete { _ =>
      log.error("Unknown controller message " + any)
      System.exit(14)
    }
  }

}