/*
 * Copyright (C) 2015 Red Bull Media House GmbH - all rights reserved.
 */

package com.rbmhtechnology.eventuate.log

import java.nio.ByteBuffer

import akka.actor.Actor

import org.iq80.leveldb.DBIterator

trait LeveldbReplicationProgressMap extends Actor { this: LeveldbEventLog with LeveldbNumericIdentifierMap =>
  import LeveldbReplicationProgressMap._
  import LeveldbEventLog._

  private var rpMap: Map[Int, Long] = Map.empty

  def writeReplicationProgress(logId: String, logSnr: Long): Unit = {
    val nid = numericId(logId)
    rpMap = rpMap + (nid -> logSnr)
    leveldb.put(rpKeyBytes(nid), longBytes(logSnr))
  }

  def readReplicationProgress(logId: String): Long = rpMap.get(numericId(logId)) match {
    case None    => 0L
    case Some(v) => v
  }

  private def readRpMap(): Map[Int, Long] = withIterator { iter =>
    iter.seek(rpKeyBytes(0))
    readRpMap(Map.empty, iter)
  }

  private def readRpMap(pathMap: Map[Int, Long], iter: DBIterator): Map[Int, Long] = {
    if (!iter.hasNext) pathMap else {
      val nextEntry = iter.next()
      val nextKey = rpKey(nextEntry.getKey)
      if (nextKey == rpKeyEnd) pathMap else {
        val nextVal = longFromBytes(nextEntry.getValue)
        readRpMap(pathMap + (nextKey -> nextVal), iter)
      }
    }
  }

  override def preStart() {
    super.preStart()
    leveldb.put(rpKeyEndBytes, Array.empty[Byte])
    rpMap = readRpMap()
  }
}

object LeveldbReplicationProgressMap {
  val rpKeyEnd: Int = Int.MaxValue
  val rpKeyEndBytes: Array[Byte] = {
    val bb = ByteBuffer.allocate(12)
    bb.putLong(-1L)
    bb.putInt(rpKeyEnd)
    bb.array
  }

  def rpKeyBytes(nid: Int): Array[Byte] = {
    val bb = ByteBuffer.allocate(12)
    bb.putLong(-1L)
    bb.putInt(nid)
    bb.array
  }

  def rpKey(a: Array[Byte]): Int = {
    val bb = ByteBuffer.wrap(a)
    bb.getLong
    bb.getInt
  }
}