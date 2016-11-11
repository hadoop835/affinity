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

package io.amient.affinity.core.storage.rocksdb

import java.nio.ByteBuffer
import java.nio.file.{Files, Paths}

import com.typesafe.config.Config
import io.amient.affinity.core.storage.MemStore
import io.amient.affinity.core.util.ByteUtils
import org.rocksdb.{Options, RocksDB, RocksDBException}


object MemStoreRocksDb {
  def CONFIG_ROCKSDB_DATA_PATH = "memstore.rocksdb.data.path"
}

class MemStoreRocksDb(config: Config, partition: Int) extends MemStore {

  private val pathToData = config.getString(MemStoreRocksDb.CONFIG_ROCKSDB_DATA_PATH) + s"/$partition"
  private val containerPath = Paths.get(pathToData).getParent.toAbsolutePath

  RocksDB.loadLibrary()
  private val rocksOptions = new Options().setCreateIfMissing(true)
  private val internal = try {
    Files.createDirectories(containerPath)
    RocksDB.open(rocksOptions, pathToData)
  } catch {
    case e:RocksDBException => throw new RuntimeException(e)
  }

  override def close: Unit = internal.close()

  override def apply(key: MK): Option[MV] = get(ByteUtils.bufToArray(key))

  override def iterator:Iterator[(MK,MV)] = new Iterator[(MK, MV)] {
    val rocksIterator = internal.newIterator()
    rocksIterator.seekToFirst()

    override def hasNext: Boolean = if (rocksIterator.isValid) {
      rocksIterator.close
      false
    } else {
      true
    }

    override def next(): (MK, MV) = (ByteBuffer.wrap(rocksIterator.key()), ByteBuffer.wrap(rocksIterator.value()))

  }

  override protected[storage] def update(key: MK, value: MV): Option[MV] = {
    val keyBytes = ByteUtils.bufToArray(key)
    val prev = get(keyBytes)
    internal.put(keyBytes, ByteUtils.bufToArray(value))
    prev
  }

  override protected[storage] def remove(key: MK):Option[MV] = {
    val keyBytes = ByteUtils.bufToArray(key)
    val prev = get(keyBytes)
    internal.remove(keyBytes)
    prev
  }

  private def get(key: Array[Byte]): Option[MV] = internal.get(key) match {
    case null => None
    case value => Some(ByteBuffer.wrap(value))
  }

}
