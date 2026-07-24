package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.test.FlatSpec

import scala.collection.mutable

class SimClockSpecification extends FlatSpec {
  "SimClock" should "fire scheduled events in time order, tie-broken by schedule order" in {
    val clock = new SimClock(seed = 1L)
    val fired = mutable.ListBuffer.empty[String]

    clock.schedule(30)(fired += "c")
    clock.schedule(10)(fired += "a")
    clock.schedule(10)(fired += "b") // same time as "a", scheduled after it -> fires after "a"
    clock.schedule(20)(fired += "d")

    val count = clock.runToQuiescence()

    count should be(4)
    fired.toList should be(List("a", "b", "d", "c"))
    clock.currentTime should be(SimTime(30))
  }

  it should "be perfectly reproducible for a fixed seed across two independent runs" in {
    def runOnce(): List[Int] = {
      val clock = new SimClock(seed = 42L)
      val fired = mutable.ListBuffer.empty[Int]
      (0 until 50).foreach { i =>
        val delay = clock.random.nextInt(100)
        clock.schedule(delay)(fired += i)
      }
      clock.runToQuiescence()
      fired.toList
    }

    runOnce() should be(runOnce())
  }
}
