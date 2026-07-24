package com.decentralchain.consensus.hotstuff.sim

import scala.collection.mutable

/** Virtual time in the simulation, in whole milliseconds. Never derived from the wall clock. */
final case class SimTime(millis: Long) extends Ordered[SimTime] {
  def +(delta: Long): SimTime    = SimTime(millis + delta)
  def compare(that: SimTime): Int = millis.compareTo(that.millis)
}

private final case class ScheduledEvent(at: SimTime, seq: Long, run: () => Unit)

/** Deterministic virtual-time event loop: no threads, no wall clock. Replaying the same seed against
  * the same sequence of `schedule` calls always produces the same firing order, because ties at the
  * same `SimTime` are broken by `seq` (schedule call order), not by any hash-based or thread-based
  * nondeterminism.
  */
final class SimClock(seed: Long) {
  private val rng    = new scala.util.Random(seed)
  private var now     = SimTime(0)
  private var seqCtr  = 0L
  private val ordering = Ordering.by[ScheduledEvent, (Long, Long)](e => (e.at.millis, e.seq)).reverse
  private val queue    = new mutable.PriorityQueue[ScheduledEvent]()(using ordering)

  def currentTime: SimTime      = now
  def random: scala.util.Random = rng

  /** Schedule `run` to fire after `delayMillis` of virtual time (must be >= 0). */
  def schedule(delayMillis: Long)(run: => Unit): Unit = {
    require(delayMillis >= 0, s"delayMillis must be >= 0, got $delayMillis")
    seqCtr += 1
    queue.enqueue(ScheduledEvent(now + delayMillis, seqCtr, () => run))
  }

  /** Drain events until the queue is empty or `maxEvents` have fired (a runaway-loop guard). Returns
    * the number of events actually fired.
    */
  def runToQuiescence(maxEvents: Int = 200000): Int = {
    var fired = 0
    while (queue.nonEmpty && fired < maxEvents) {
      val ev = queue.dequeue()
      now = ev.at
      ev.run()
      fired += 1
    }
    fired
  }
}
