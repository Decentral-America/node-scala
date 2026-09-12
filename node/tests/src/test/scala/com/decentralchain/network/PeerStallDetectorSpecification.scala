package com.decentralchain.network

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Pure counter tests for `PeerStallDetector` — the in-process replacement for
  * `peer-watchdog.yml`'s external SSH+docker-restart remedy. See
  * docs/superpowers/plans/2026-09-12-inprocess-peer-stall-detection.md.
  */
class PeerStallDetectorSpecification extends AnyFreeSpec with Matchers {

  "PeerStallDetector" - {
    "never fires while connections exist, regardless of tick count" in {
      val detector = new PeerStallDetector(threshold = 3)
      (1 to 100).foreach { _ =>
        detector.tick(hasConnections = true, hasCandidate = false) shouldBe false
      }
    }

    "never fires while a candidate is available (not truly stuck, just mid-reconnect)" in {
      val detector = new PeerStallDetector(threshold = 3)
      (1 to 100).foreach { _ =>
        detector.tick(hasConnections = false, hasCandidate = true) shouldBe false
      }
    }

    "fires exactly once, on the tick where the threshold is first reached" in {
      val detector = new PeerStallDetector(threshold = 3)
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 1
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 2
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe true  // 3 -- fires
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 4 -- already fired, stays quiet
    }

    "resets and can fire again after a successful connection interrupts the stall" in {
      val detector = new PeerStallDetector(threshold = 3)
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false
      detector.tick(hasConnections = true, hasCandidate = false) shouldBe false // reconnected, resets
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 1 again
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 2
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe true  // 3 -- fires again
    }

    "explicit reset() also clears the counter" in {
      val detector = new PeerStallDetector(threshold = 3)
      detector.tick(hasConnections = false, hasCandidate = false)
      detector.tick(hasConnections = false, hasCandidate = false)
      detector.reset()
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 1, not 3
    }
  }
}
