package com.decentralchain.utils

import scala.util.control.NonFatal

/** Wraps a per-element handler passed to Monix `Observable.foreach`. Monix's `ForeachSubscriber`
  * turns a NonFatal exception thrown by the handler into `onError` + `Ack.Stop`, which ends the
  * subscription permanently. A single bad element must never stop intake, so failures are reported
  * through `onFailure` and the stream keeps being consumed.
  */
object SafeHandler extends ScorexLogging {
  def apply[A](name: String, onFailure: (A, Throwable) => Unit)(f: A => Unit): A => Unit =
    (a: A) =>
      try f(a)
      catch {
        case NonFatal(e) =>
          log.debug(s"[$name] element handler failed", e)
          try onFailure(a, e)
          catch { case NonFatal(e2) => log.warn(s"[$name] failure callback failed: ${e2.getMessage}") }
      }
}
