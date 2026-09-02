package com.decentralchain.consensus.hotstuff

import com.decentralchain.test.FlatSpec

import java.nio.file.{Files, Paths}

/** Audit finding F-5 (MEDIUM) regression coverage: the watchdog's original stall detector counted ANY
  * accepted-QC action (`Committed` OR `EnteredView`) as progress via `recordProgress()`, so a replica
  * that keeps forming/receiving valid PREPARE/PRE_COMMIT QCs -- advancing the view every round -- but
  * never reaches a COMMIT QC would call `recordProgress()` on essentially every tick, and the view-based
  * `consecutiveStalledTicks` counter would never accumulate. That is a plausible description of the live
  * incident this watchdog was built for: `hotStuffFinalizedHeight` frozen for 2h19m / 604+ blocks while
  * views kept advancing.
  *
  * The fix adds a second, independent, commit-specific staleness counter driven ONLY by `recordCommit()`
  * (wired in `Application.scala`'s `hsOnAction` to fire alongside `recordProgress()` for a `Committed`
  * action, but NOT for a bare `EnteredView`) -- `check()` now fires recovery when EITHER the view-based
  * OR the commit-specific counter trips. This is safe to ship only because F-2 (`resetLocalSafetyState`
  * preserving `lastVotedView`) already landed -- see `HotStuffWatchdog`'s class doc.
  *
  * Mirrors `HotStuffWatchdogSpecification`'s style: exercise `check()`/`recordProgress()`/`recordCommit()`
  * directly (not through the coordinator), since the watchdog's own state machine is what's under test.
  */
class HotStuffWatchdogCommitStallSpecification extends FlatSpec {

  private def tempLockPath() = {
    val dir = Files.createTempDirectory("hotstuff-watchdog-commit-stall-spec")
    dir.toFile.deleteOnExit()
    Paths.get(dir.toString, "locked-qc.dat")
  }

  private def newWatchdog(stallThreshold: Int, commitStallThreshold: Int): (HotStuffWatchdog, () => Int) = {
    var resetCount = 0
    val watchdog = new HotStuffWatchdog(
      committeeNonEmpty = () => true,
      lockPath = tempLockPath(),
      resetInMemoryState = () => resetCount += 1,
      stallThreshold = stallThreshold,
      commitStallThreshold = commitStallThreshold
    )
    (watchdog, () => resetCount)
  }

  "a stream of EnteredView-only progress (views advancing, nothing ever committing)" should
    "fire recovery once the commit-specific threshold is exceeded, even though recordProgress() fires every tick" in {
      val (wd, resets) = newWatchdog(stallThreshold = 5, commitStallThreshold = 10)
      var firedAtTick: Option[Int] = None
      (1 to 15).foreach { tick =>
        wd.recordProgress() // view-based signal: fires EVERY tick, exactly the F-5 hazard (EnteredView only)
        val fired = wd.check()
        if (fired && firedAtTick.isEmpty) firedAtTick = Some(tick)
      }
      // The view-based counter (`consecutiveStalledTicks`) is reset to 0 on every tick by
      // `recordProgress()` alone -- BEFORE the fix this stream would never fire, no matter how long it
      // ran. With the fix, the commit-specific counter accumulates independently and fires at tick 10.
      firedAtTick should be(Some(10))
      resets() should be(1)
    }

  it should "NOT fire (from the commit signal) before commitStallThreshold ticks have elapsed" in {
    val (wd, resets) = newWatchdog(stallThreshold = 100, commitStallThreshold = 10)
    (1 to 9).foreach { _ =>
      wd.recordProgress()
      wd.check() should be(false)
    }
    resets() should be(0)
  }

  "a mixed stream with a Committed action inside the commit-stall window" should
    "reset the commit-specific counter, so recovery never fires purely from view-based progress" in {
      val (wd, resets) = newWatchdog(stallThreshold = 100, commitStallThreshold = 10)
      (1 to 8).foreach { _ =>
        wd.recordProgress() // EnteredView-only progress
        wd.check() should be(false)
      }
      // A genuine commit lands inside the window (tick 9): resets the commit-specific counter.
      wd.recordCommit()
      wd.recordProgress()
      wd.check() should be(false)

      // A FULL fresh commitStallThreshold's worth of EnteredView-only ticks must elapse from here before
      // it fires again -- the prior 8 ticks must not carry over.
      (1 to 9).foreach { _ =>
        wd.recordProgress()
        wd.check() should be(false)
      }
      resets() should be(0)
      wd.recordProgress()
      wd.check() should be(true) // tick 10 since the last commit -- now it fires
      resets() should be(1)
    }

  "the view-based signal" should "still fire on its own (unaffected by the commit-specific counter) exactly as before F-5" in {
    val (wd, resets) = newWatchdog(stallThreshold = 3, commitStallThreshold = 1000)
    // No recordProgress()/recordCommit() at all -- a genuine full stall (no QCs forming at all).
    wd.check() should be(false) // 1
    wd.check() should be(false) // 2
    wd.check() should be(true)  // 3 -- view-based counter trips first, well before the commit threshold
    resets() should be(1)
  }

  "recordCommit()" should "also count as progress on the view-based signal (a Committed action is a superset of progress)" in {
    val (wd, resets) = newWatchdog(stallThreshold = 3, commitStallThreshold = 1000)
    wd.check() should be(false) // 1
    // `recordCommit()` and `recordProgress()` are TWO SEPARATE flags/counters (see the class doc) --
    // `recordCommit()` alone does NOT imply the view-based `progressSinceLastCheck` flag. This mirrors
    // Application.scala's real `hsOnAction` wiring, which calls BOTH for a genuine `Committed` action
    // (recordCommit() for the new commit-specific signal, recordProgress() for the pre-existing
    // view-based one) -- never `recordCommit()` alone.
    wd.recordCommit()
    wd.recordProgress()
    wd.check() should be(false) // view-based counter reset by the commit -- would need another full 3 to fire
    resets() should be(0)

    wd.check() should be(false) // 1 (post-commit)
    wd.check() should be(false) // 2 (post-commit)
    wd.check() should be(true)  // 3 (post-commit) -- fires on the view-based signal alone
    resets() should be(1)
  }

  "the empty-committee case" should
    "reset BOTH the view-based and commit-specific counters, and never fire, exactly as before F-5" in {
      var committeeEmpty = false
      var resetCount      = 0
      val wd              = new HotStuffWatchdog(
        committeeNonEmpty = () => !committeeEmpty,
        lockPath = tempLockPath(),
        resetInMemoryState = () => resetCount += 1,
        stallThreshold = 3,
        commitStallThreshold = 5
      )
      wd.check() should be(false) // 1 (non-empty, no progress on either signal)
      committeeEmpty = true
      (1 to 20).foreach { _ => wd.check() should be(false) } // must never fire while empty, on EITHER signal
      resetCount should be(0)
    }

  "existing view-based backoff/suspension behaviour" should
    "be unaffected by a commit-specific threshold that never trips (regression guard for the F-5 refactor)" in {
      val (wd, resets) = newWatchdog(stallThreshold = 2, commitStallThreshold = 1000)
      var fireTicks     = Vector.empty[Int]
      (1 to 60).foreach { tick =>
        if (wd.check()) fireTicks :+= tick
      }
      // Identical sequence to HotStuffWatchdogBackoffSpecification's exponential-backoff assertion:
      // 2, 4, 8, 16, 32 -- proves the F-5 refactor of check()/fireRecovery preserved the pre-existing
      // view-based backoff math exactly.
      fireTicks should be(Vector(2, 4, 8, 16, 32))
      resets() should be(5)
    }
}
