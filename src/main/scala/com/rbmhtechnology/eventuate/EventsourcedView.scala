/*
 * Copyright (C) 2015 Red Bull Media House GmbH - all rights reserved.
 */

package com.rbmhtechnology.eventuate

import akka.actor._

trait EventsourcedView extends Eventsourced with ConditionalCommands with Stash {
  import EventLogProtocol._

  private val initiating: Receive = {
    case Replaying(event, iid) => if (iid == instanceId) {
      onDurableEvent(event)
    }
    case ReplaySuccess(iid) => if (iid == instanceId) {
      context.become(initiated)
      conditionChanged(lastTimestamp)
      onRecoverySuccess()
      unstashAll()
    }
    case ReplayFailure(cause, iid) => if (iid == instanceId) {
      context.stop(self)
    }
    case other =>
      stash()
  }

  private val initiated: Receive = {
    case Written(event) => if (event.sequenceNr > lastSequenceNr) {
      onDurableEvent(event)
      conditionChanged(event.timestamp)
    }
    case ConditionalCommand(condition, cmd) =>
      conditionalSend(condition, cmd)
    case cmd =>
      onCommand(cmd)
  }

  final def receive = initiating

  override def preStart(): Unit =
    log ! Replay(1, self, instanceId)
}

/**
 * Java API.
 */
abstract class AbstractEventsourcedView(val processId: String, val log: ActorRef) extends AbstractEventsourced with EventsourcedView