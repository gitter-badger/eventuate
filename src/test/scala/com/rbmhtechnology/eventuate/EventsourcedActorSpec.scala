/*
 * Copyright (C) 2015 Red Bull Media House GmbH - all rights reserved.
 */

package com.rbmhtechnology.eventuate

import scala.util._

import akka.actor._
import akka.testkit._

import org.scalatest._

object EventsourcedActorSpec {
  val processIdA = "A"
  val processIdB = "B"
  val logId = "log"

  case class Cmd(payload: Any, num: Int = 1)
  case class CmdDelayed(payload: Any)
  case class Ping(i: Int)
  case class Pong(i: Int)

  class TestEventsourcedActor(
      val logProbe: ActorRef,
      val dstProbe: ActorRef,
      val errProbe: ActorRef,
      override val sync: Boolean) extends EventsourcedActor {

    val processId = EventsourcedActorSpec.processIdA
    val log = logProbe

    override def onCommand: Receive = {
      case "boom" => throw boom
      case Ping(i) => dstProbe ! Pong(i)
      case "test-handler-order" =>
        persist("a")(r => dstProbe ! ((s"${r.get}-1", currentTime, lastTimestamp, lastSequenceNr)))
        persist("b")(r => dstProbe ! ((s"${r.get}-2", currentTime, lastTimestamp, lastSequenceNr)))
      case CmdDelayed(p) =>
        delay(p)(p => dstProbe ! ((p, currentTime, lastTimestamp, lastSequenceNr)))
      case Cmd(p, num) => 1 to num foreach { i =>
        persist(s"${p}-${i}") {
          case Success("boom") => throw boom
          case Success(evt) => dstProbe ! ((evt, currentTime, lastTimestamp, lastSequenceNr))
          case Failure(err) => errProbe ! ((err, currentTime, lastTimestamp, lastSequenceNr))
        }
      }
    }

    override def onEvent: Receive = {
      case "boom" => throw boom
      case evt if evt != "x" => dstProbe ! ((evt, currentTime, lastTimestamp, lastSequenceNr))
    }
  }

  def eventA(payload: Any, sequenceNr: Long, timestamp: VectorTime): DurableEvent =
    DurableEvent(payload, timestamp, processIdA, logId, logId, sequenceNr, sequenceNr)

  def eventB(payload: Any, sequenceNr: Long, timestamp: VectorTime): DurableEvent =
    DurableEvent(payload, timestamp, processIdB, logId, logId, sequenceNr, sequenceNr)

  def timestampA(timeA: Long): VectorTime =
    VectorTime(processIdA -> timeA)

  def timestampAB(timeA: Long, timeB: Long): VectorTime =
    VectorTime(processIdA -> timeA, processIdB -> timeB)
}

class EventsourcedActorSpec extends TestKit(ActorSystem("test")) with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import EventsourcedActorSpec._
  import EventLogProtocol._

  var instanceId: Int = _
  var logProbe: TestProbe = _
  var dstProbe: TestProbe = _
  var errProbe: TestProbe = _

  override def beforeEach(): Unit = {
    instanceId = Eventsourced.instanceIdCounter.get
    logProbe = TestProbe()
    dstProbe = TestProbe()
    errProbe = TestProbe()
  }

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  def unrecoveredActor(sync: Boolean = true): ActorRef =
    system.actorOf(Props(new TestEventsourcedActor(logProbe.ref, dstProbe.ref, errProbe.ref, sync)))

  def recoveredActor(sync: Boolean = true): ActorRef = {
    val actor = unrecoveredActor(sync)
    logProbe.expectMsg(Replay(1, actor, instanceId))
    actor ! ReplaySuccess(instanceId)
    actor
  }

  "An EventsourcedActor" must {
    "recover from replayed local events" in {
      val actor = unrecoveredActor()
      logProbe.expectMsg(Replay(1, actor, instanceId))
      actor ! Replaying(eventA("a", 1, timestampAB(1, 0)), instanceId)
      actor ! Replaying(eventA("b", 2, timestampAB(2, 0)), instanceId)
      actor ! ReplaySuccess(instanceId)
      dstProbe.expectMsg(("a", timestampAB(1, 0), timestampAB(1, 0), 1))
      dstProbe.expectMsg(("b", timestampAB(2, 0), timestampAB(2, 0), 2))
    }
    "recover from replayed local and foreign events" in {
      val actor = unrecoveredActor()
      actor ! Replaying(eventA("a", 1, timestampAB(1, 0)), instanceId)
      actor ! Replaying(eventB("b", 2, timestampAB(0, 1)), instanceId)
      actor ! Replaying(eventB("c", 3, timestampAB(0, 2)), instanceId)
      actor ! Replaying(eventA("d", 4, timestampAB(2, 0)), instanceId)
      actor ! Replaying(eventA("e", 5, timestampAB(3, 0)), instanceId)
      actor ! Replaying(eventA("f", 6, timestampAB(4, 0)), instanceId)
      actor ! Replaying(eventA("g", 7, timestampAB(7, 2)), instanceId)
      // h with snr = 8 not persisted because of write failure
      // i with snr = 9 not persisted because of write failure
      actor ! Replaying(eventA("j", 10, timestampAB(10, 2)), instanceId)
      actor ! ReplaySuccess(instanceId)
      dstProbe.expectMsg(("a", timestampAB(1, 0), timestampAB(1, 0), 1))
      dstProbe.expectMsg(("b", timestampAB(2, 1), timestampAB(0, 1), 2))
      dstProbe.expectMsg(("c", timestampAB(3, 2), timestampAB(0, 2), 3))
      dstProbe.expectMsg(("d", timestampAB(4, 2), timestampAB(2, 0), 4))
      dstProbe.expectMsg(("e", timestampAB(5, 2), timestampAB(3, 0), 5))
      dstProbe.expectMsg(("f", timestampAB(6, 2), timestampAB(4, 0), 6))
      dstProbe.expectMsg(("g", timestampAB(7, 2), timestampAB(7, 2), 7))
      dstProbe.expectMsg(("j", timestampAB(10, 2), timestampAB(10, 2), 10))
    }
    "retry recovery on failure" in {
      val actor = unrecoveredActor()
      logProbe.expectMsg(Replay(1, actor, instanceId))
      actor ! Replaying(eventA("a", 1, timestampAB(1, 0)), instanceId)
      actor ! Replaying(eventA("boom", 2, timestampAB(3, 0)), instanceId)
      actor ! Replaying(eventA("c", 3, timestampAB(2, 0)), instanceId)
      actor ! ReplaySuccess(instanceId)
      logProbe.expectMsg(Replay(1, actor, instanceId + 1))
      actor ! Replaying(eventA("a", 1, timestampAB(1, 0)), instanceId + 1)
      actor ! Replaying(eventA("b", 2, timestampAB(2, 0)), instanceId + 1)
      actor ! Replaying(eventA("c", 3, timestampAB(3, 0)), instanceId + 1)
      actor ! ReplaySuccess(instanceId + 1)
      dstProbe.expectMsg(("a", timestampAB(1, 0), timestampAB(1, 0), 1))
      dstProbe.expectMsg(("a", timestampAB(1, 0), timestampAB(1, 0), 1))
      dstProbe.expectMsg(("b", timestampAB(2, 0), timestampAB(2, 0), 2))
      dstProbe.expectMsg(("c", timestampAB(3, 0), timestampAB(3, 0), 3))
    }
    "stash commands during recovery and handle them after initial recovery" in {
      val actor = unrecoveredActor()
      actor ! Ping(1)
      actor ! Replaying(eventA("a", 1, timestampAB(1, 0)), instanceId)
      actor ! Ping(2)
      actor ! Replaying(eventA("b", 2, timestampAB(2, 0)), instanceId)
      actor ! Ping(3)
      actor ! ReplaySuccess(instanceId)
      dstProbe.expectMsg(("a", timestampAB(1, 0), timestampAB(1, 0), 1))
      dstProbe.expectMsg(("b", timestampAB(2, 0), timestampAB(2, 0), 2))
      dstProbe.expectMsg(Pong(1))
      dstProbe.expectMsg(Pong(2))
      dstProbe.expectMsg(Pong(3))
    }
    "stash commands during recovery and handle them after retried recovery" in {
      val actor = unrecoveredActor()
      logProbe.expectMsg(Replay(1, actor, instanceId))
      actor ! Replaying(eventA("a", 1, timestampAB(1, 0)), instanceId)
      actor ! Ping(1)
      actor ! Replaying(eventA("boom", 2, timestampAB(1, 0)), instanceId)
      actor ! Ping(2)
      actor ! Replaying(eventA("c", 3, timestampAB(1, 0)), instanceId)
      actor ! ReplaySuccess(instanceId)
      logProbe.expectMsg(Replay(1, actor, instanceId + 1))
      actor ! Replaying(eventA("a", 1, timestampAB(1, 0)), instanceId + 1)
      actor ! Replaying(eventA("b", 2, timestampAB(2, 0)), instanceId + 1)
      actor ! Replaying(eventA("c", 3, timestampAB(3, 0)), instanceId + 1)
      actor ! ReplaySuccess(instanceId + 1)
      dstProbe.expectMsg(("a", timestampAB(1, 0), timestampAB(1, 0), 1))
      dstProbe.expectMsg(("a", timestampAB(1, 0), timestampAB(1, 0), 1))
      dstProbe.expectMsg(("b", timestampAB(2, 0), timestampAB(2, 0), 2))
      dstProbe.expectMsg(("c", timestampAB(3, 0), timestampAB(3, 0), 3))
      dstProbe.expectMsg(Pong(1))
      dstProbe.expectMsg(Pong(2))
    }
    "ignore live events that have already been consumed during recovery" in {
      val actor = unrecoveredActor()
      actor ! Replaying(eventA("a", 1, timestampAB(1, 0)), instanceId)
      actor ! Written(eventB("b", 2, timestampAB(0, 1))) // live event
      actor ! Written(eventB("c", 3, timestampAB(0, 2))) // live event
      actor ! Written(eventB("d", 4, timestampAB(0, 3))) // live event
      actor ! Replaying(eventB("b", 2, timestampAB(0, 1)), instanceId)
      actor ! Replaying(eventB("c", 3, timestampAB(0, 2)), instanceId)
      actor ! ReplaySuccess(instanceId)
      dstProbe.expectMsg(("a", timestampAB(1, 0), timestampAB(1, 0), 1))
      dstProbe.expectMsg(("b", timestampAB(2, 1), timestampAB(0, 1), 2))
      dstProbe.expectMsg(("c", timestampAB(3, 2), timestampAB(0, 2), 3))
      dstProbe.expectMsg(("d", timestampAB(4, 3), timestampAB(0, 3), 4))
    }
    "update the vector clock even if event is not handled during recovery" in {
      val actor = unrecoveredActor()
      actor ! Replaying(eventA("a", 1, timestampAB(1, 0)), instanceId)
      actor ! Replaying(eventB("x", 2, timestampAB(0, 1)), instanceId)
      actor ! Replaying(eventA("c", 3, timestampAB(2, 0)), instanceId)
      actor ! ReplaySuccess(instanceId)
      dstProbe.expectMsg(("a", timestampAB(1, 0), timestampAB(1, 0), 1))
      dstProbe.expectMsg(("c", timestampAB(3, 1), timestampAB(2, 0), 3))
    }
  }

  "An EventsourcedActor" when {
    "in sync mode" must {
      "stash further commands while persistence is in progress" in {
        val actor = recoveredActor(sync = true)
        actor ! Cmd("a", 2)
        actor ! Ping(1)
        val write = logProbe.expectMsgClass(classOf[Write])
        write.events(0).payload should be("a-1")
        write.events(1).payload should be("a-2")
        write.events(0).timestamp should be(timestampA(1))
        write.events(1).timestamp should be(timestampA(2))
        actor ! WriteSuccess(write.events(0).copy(targetLogSequenceNr = 1L), instanceId)
        actor ! WriteSuccess(write.events(1).copy(targetLogSequenceNr = 2L), instanceId)
        dstProbe.expectMsg(("a-1", timestampA(2), timestampA(1), 1))
        dstProbe.expectMsg(("a-2", timestampA(2), timestampA(2), 2))
        dstProbe.expectMsg(Pong(1))
      }
      "process further commands if persist is aborted by exception in persist handler" in {
        val actor = recoveredActor(sync = true)
        actor ! Cmd("a", 2)
        actor ! Cmd("b", 2)
        val write1 = logProbe.expectMsgClass(classOf[Write])
        actor ! WriteSuccess(write1.events(0).copy(targetLogSequenceNr = 1L, payload = "boom"), instanceId)
        actor ! WriteSuccess(write1.events(1).copy(targetLogSequenceNr = 2L), instanceId)
        logProbe.expectMsg(Replay(1, actor, instanceId + 1))
        actor ! Replaying(write1.events(0).copy(targetLogSequenceNr = 1L), instanceId + 1)
        actor ! Replaying(write1.events(1).copy(targetLogSequenceNr = 2L), instanceId + 1)
        actor ! ReplaySuccess(instanceId + 1)
        val write2 = logProbe.expectMsgClass(classOf[Write])
        write2.events(0).payload should be("b-1")
        write2.events(1).payload should be("b-2")
        actor ! WriteSuccess(write2.events(0).copy(targetLogSequenceNr = 3L), instanceId + 1)
        actor ! WriteSuccess(write2.events(1).copy(targetLogSequenceNr = 4L), instanceId + 1)
        dstProbe.expectMsg(("a-1", timestampA(1), timestampA(1), 1))
        dstProbe.expectMsg(("a-2", timestampA(2), timestampA(2), 2))
        dstProbe.expectMsg(("b-1", timestampA(4), timestampA(3), 3))
        dstProbe.expectMsg(("b-2", timestampA(4), timestampA(4), 4))
      }
    }
    "in async mode" must {
      "process further commands while persistence is in progress" in {
        val actor = recoveredActor(sync = false)
        actor ! Cmd("a", 2)
        actor ! Ping(1)
        val write = logProbe.expectMsgClass(classOf[Write])
        write.events(0).payload should be("a-1")
        write.events(1).payload should be("a-2")
        write.events(0).timestamp should be(timestampA(1))
        write.events(1).timestamp should be(timestampA(2))
        actor ! WriteSuccess(write.events(0).copy(targetLogSequenceNr = 1L), instanceId)
        actor ! WriteSuccess(write.events(1).copy(targetLogSequenceNr = 2L), instanceId)
        dstProbe.expectMsg(Pong(1))
        dstProbe.expectMsg(("a-1", timestampA(2), timestampA(1), 1))
        dstProbe.expectMsg(("a-2", timestampA(2), timestampA(2), 2))
      }
      "process further commands if persist is aborted by exception in command handler" in {
        val actor = recoveredActor(sync = false)
        actor ! Cmd("a", 2)
        actor ! "boom"
        actor ! Cmd("b", 2)
        val write1 = logProbe.expectMsgClass(classOf[Write])
        actor ! WriteSuccess(write1.events(0).copy(targetLogSequenceNr = 1L, payload = "boom"), instanceId)
        actor ! WriteSuccess(write1.events(1).copy(targetLogSequenceNr = 2L), instanceId)
        logProbe.expectMsg(Replay(1, actor, instanceId + 1))
        actor ! Replaying(write1.events(0).copy(targetLogSequenceNr = 1L), instanceId + 1)
        actor ! Replaying(write1.events(1).copy(targetLogSequenceNr = 2L), instanceId + 1)
        actor ! ReplaySuccess(instanceId + 1)
        val write2 = logProbe.expectMsgClass(classOf[Write])
        write2.events(0).payload should be("b-1")
        write2.events(1).payload should be("b-2")
        actor ! WriteSuccess(write2.events(0).copy(targetLogSequenceNr = 3L), instanceId + 1)
        actor ! WriteSuccess(write2.events(1).copy(targetLogSequenceNr = 4L), instanceId + 1)
        dstProbe.expectMsg(("a-1", timestampA(1), timestampA(1), 1))
        dstProbe.expectMsg(("a-2", timestampA(2), timestampA(2), 2))
        dstProbe.expectMsg(("b-1", timestampA(4), timestampA(3), 3))
        dstProbe.expectMsg(("b-2", timestampA(4), timestampA(4), 4))
      }
      "delay commands relative to events" in {
        val actor = recoveredActor(sync = false)
        actor ! Cmd("a")
        actor ! CmdDelayed("b")
        actor ! Cmd("c")
        val write1 = logProbe.expectMsgClass(classOf[Write])
        val delay = logProbe.expectMsgClass(classOf[Delay])
        val write2 = logProbe.expectMsgClass(classOf[Write])
        actor ! WriteSuccess(write1.events(0).copy(targetLogSequenceNr = 1L), instanceId)
        actor ! DelaySuccess(delay.commands(0), instanceId)
        actor ! WriteSuccess(write2.events(0).copy(targetLogSequenceNr = 2L), instanceId)
        dstProbe.expectMsg(("a-1", timestampA(2), timestampA(1), 1))
        dstProbe.expectMsg(("b", timestampA(2), timestampA(1), 1))
        dstProbe.expectMsg(("c-1", timestampA(2), timestampA(2), 2))
      }
    }
    "in any mode" must {
      "handle foreign events while persistence is in progress" in {
        val actor = recoveredActor(sync = true)
        actor ! Cmd("a", 2)
        val write = logProbe.expectMsgClass(classOf[Write])
        write.events(0).payload should be("a-1")
        write.events(1).payload should be("a-2")
        write.events(0).timestamp should be(timestampA(1))
        write.events(1).timestamp should be(timestampA(2))
        actor ! Written(eventB("b-1", 1, timestampAB(0, 1)))
        actor ! Written(eventB("b-2", 2, timestampAB(0, 2)))
        actor ! WriteSuccess(write.events(0).copy(targetLogSequenceNr = 3L), instanceId)
        actor ! WriteSuccess(write.events(1).copy(targetLogSequenceNr = 4L), instanceId)
        dstProbe.expectMsg(("b-1", timestampAB(3, 1), timestampAB(0, 1), 1))
        dstProbe.expectMsg(("b-2", timestampAB(4, 2), timestampAB(0, 2), 2))
        dstProbe.expectMsg(("a-1", timestampAB(4, 2), timestampA(1), 3))
        dstProbe.expectMsg(("a-2", timestampAB(4, 2), timestampA(2), 4))
      }
      "invoke persist handler in correct order" in {
        val actor = recoveredActor(sync = true)
        actor ! "test-handler-order"
        val write = logProbe.expectMsgClass(classOf[Write])
        write.events(0).payload should be("a")
        write.events(1).payload should be("b")
        actor ! WriteSuccess(write.events(0).copy(targetLogSequenceNr = 1L), instanceId)
        actor ! WriteSuccess(write.events(1).copy(targetLogSequenceNr = 2L), instanceId)
        dstProbe.expectMsg(("a-1", timestampA(2), timestampA(1), 1))
        dstProbe.expectMsg(("b-2", timestampA(2), timestampA(2), 2))
      }
      "report failed writes to persist handler" in {
        val actor = recoveredActor(sync = true)
        actor ! Cmd("a", 2)
        val write = logProbe.expectMsgClass(classOf[Write])
        actor ! WriteFailure(write.events(0).copy(targetLogSequenceNr = 1L), boom, instanceId)
        actor ! WriteFailure(write.events(1).copy(targetLogSequenceNr = 2L), boom, instanceId)
        errProbe.expectMsg((boom, timestampA(2), timestampA(1), 1))
        errProbe.expectMsg((boom, timestampA(2), timestampA(2), 2))
      }
      "not send empty write commands to log" in {
        val actor = recoveredActor(sync = true)
        actor ! Ping(1)
        actor ! Cmd("a", 2)
        val write = logProbe.expectMsgClass(classOf[Write])
        write.events(0).payload should be("a-1")
        write.events(1).payload should be("a-2")
      }
    }
  }
}
