/*
 * Copyright 2016-2017 Michal Harish, michal.harish@gmail.com
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

package io.amient.affinity.core.serde.collection

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}

import akka.actor.ExtendedActorSystem
import io.amient.affinity.core.serde.primitive.AbstractWrapSerde

class SeqSerde(system: ExtendedActorSystem) extends AbstractWrapSerde(system) {

  override def identifier: Int = 141

  override protected def fromBinaryJava(bytes: Array[Byte], manifest: Class[_]): AnyRef = {
    val di = new DataInputStream(new ByteArrayInputStream(bytes))
    val numItems = di.readInt()
    val result = ((1 to numItems) map { _ =>
      val len = di.readInt()
      val item = new Array[Byte](len)
      di.read(item)
      fromBinaryWrapped(item)
    }).toList
    di.close()
    result
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case list: Seq[_] =>
        val os = new ByteArrayOutputStream()
        val d = new DataOutputStream(os)
        d.writeInt(list.size)
        for (a: Any <- list) a match {
          case ref: AnyRef =>
            val item = toBinaryWrapped(ref)
            d.writeInt(item.length)
            d.write(item)
        }
        os.close
        os.toByteArray
    }

  }
}
