package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.test.FlatSpec

import scala.collection.mutable

class SimNetworkSpecification extends FlatSpec {
  "SimNetwork" should "deliver to every recipient except the sender when there are no faults" in {
    val clock     = new SimClock(seed = 7L)
    val network   = new SimNetwork[String](clock, nodeCount = 4, FaultProfile(dropProbability = 0.0, duplicateProbability = 0.0))
    val delivered = mutable.ListBuffer.empty[(Int, String)]

    network.send(from = 0, to = Set(0, 1, 2, 3))("hello") { case (to, m) => delivered += ((to, m)) }
    clock.runToQuiescence()

    delivered.map(_._1).sorted.toList should be(List(1, 2, 3))
  }

  it should "deliver nothing when dropProbability is 1.0" in {
    val clock     = new SimClock(seed = 7L)
    val network   = new SimNetwork[String](clock, nodeCount = 4, FaultProfile(dropProbability = 1.0))
    val delivered = mutable.ListBuffer.empty[(Int, String)]

    network.send(from = 0, to = Set(0, 1, 2, 3))("hello") { case (to, m) => delivered += ((to, m)) }
    clock.runToQuiescence()

    delivered shouldBe empty
  }

  it should "block delivery across an active partition and resume once healed" in {
    val clock     = new SimClock(seed = 7L)
    val network   = new SimNetwork[String](clock, nodeCount = 4, FaultProfile())
    val delivered = mutable.ListBuffer.empty[(Int, String)]

    network.partition(Set(0), Set(3))
    network.send(from = 0, to = Set(1, 2, 3))("a") { case (to, m) => delivered += ((to, m)) }
    clock.runToQuiescence()
    delivered.map(_._1).sorted.toList should be(List(1, 2)) // node 3 dropped by the partition

    delivered.clear()
    network.healPartition(Set(0), Set(3))
    network.send(from = 0, to = Set(1, 2, 3))("b") { case (to, m) => delivered += ((to, m)) }
    clock.runToQuiescence()
    delivered.map(_._1).sorted.toList should be(List(1, 2, 3))
  }
}
