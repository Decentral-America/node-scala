package com.decentralchain.api.observers

import com.decentralchain.events.WrappedEvent
import monix.reactive.Observer

class MonixWrappedDownstream[RequestT, EventT](s: Observer[WrappedEvent[EventT]]) extends ManualGrpcObserver[RequestT, EventT] {
  override def onNext(value: EventT): Unit = send(WrappedEvent.Next(value))

  override def onError(t: Throwable): Unit = {
    send(WrappedEvent.Failed(t))
    super.onError(t)
  }

  override def onCompleted(): Unit = {
    send(WrappedEvent.Closed)
    super.onCompleted()
  }

  private def send(event: WrappedEvent[EventT]): Unit = ifWorking(s.onNext(event))
}
