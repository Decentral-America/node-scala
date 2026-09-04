package com.decentralchain.consensus.hotstuff

import com.decentralchain.test.FlatSpec

import java.nio.file.{Files, Paths}

/** Review fix (Important #2, post-initial-landing): `HotStuffWatchdog.check()` used to reset its stall
  * counter unconditionally after firing, with no memory of whether a PRIOR recovery had actually helped --
  * so a wedge the recovery action genuinely cannot fix (e.g. a correlated fault wedging several replicas
  * at once, where clearing one replica's own local lock changes nothing about why quorum can't form)
  * would cause this watchdog to blank local safety state every `stallThreshold` ticks, forever, with no
  * escalation and no human ever paged. This spec covers the fix: exponential backoff after an ineffective
  * recovery, a hard cap after which auto-recovery suspends itself (logging only), and a full reset of
  * that backoff/suspension state the moment real progress is next observed.
  */
class HotStuffWatchdogBackoffSpecification extends FlatSpec {

  private def tempLockPath() = {
    val dir = Files.createTempDirectory("hotstuff-watchdog-backoff-spec")
    dir.toFile.deleteOnExit()
    Paths.get(dir.toString, "locked-qc.dat")
  }

  private def newWatchdog(threshold: Int = 3, maxConsecutiveRecoveries: Int = 3): (HotStuffWatchdog, () => Int) = {
    var resetCount = 0
    val watchdog   = new HotStuffWatchdog(
      committeeNonEmpty = () => true,
      lockPath = tempLockPath(),
      resetInMemoryState = () => resetCount += 1,
      stallThreshold = threshold,
      maxConsecutiveRecoveries = maxConsecutiveRecoveries
    )
    (watchdog, () => resetCount)
  }

  "an unfixable wedge (zero progress ever, forever)" should
    "back off exponentially after each ineffective recovery, doubling the ticks required before the next one" in {
      val (wd, _)   = newWatchdog(threshold = 2, maxConsecutiveRecoveries = 10) // high cap -- isolate backoff timing alone
      var fireTicks = Vector.empty[Int]
      (1 to 60).foreach { tick =>
        if (wd.check()) fireTicks :+= tick
      }
      // Counter resets to 0 after EVERY fire (not just the first), and the ineffectiveness check that
      // triggers doubling happens against the counter starting fresh from the tick right after the
      // PREVIOUS fire -- so each successive gap is exactly `effectiveThreshold` (not cumulative from the
      // start), and `effectiveThreshold` doubles (2 -> 4 -> 8 -> 16) each time. Fires land at ticks 2,
      // 2+2=4, 4+4=8, 8+8=16, 16+16=32 -- i.e. 2, 4, 8, 16, 32 within the 60-tick window.
      fireTicks should be(Vector(2, 4, 8, 16, 32))
    }

  it should "suspend auto-recovery entirely once maxConsecutiveRecoveries consecutive recoveries have all failed to help" in {
    val (wd, resets) = newWatchdog(threshold = 2, maxConsecutiveRecoveries = 3)
    wd.isAutoRecoverySuspended should be(false)

    // Backoff sequence with threshold=2, cap=3 (each fire resets the counter and, if ineffective,
    // doubles effectiveThreshold before the NEXT attempt): fires at ticks 2, 4, 8 -- exactly
    // `maxConsecutiveRecoveries` (3) fires -- then suspension kicks in once the 3rd fire is itself later
    // judged ineffective, well within a 40-tick window (an unbounded threshold=2 loop would instead have
    // fired ~20 times in that window).
    var fires = 0
    (1 to 40).foreach { _ => if (wd.check()) fires += 1 }

    wd.isAutoRecoverySuspended should be(true)
    fires should be(3)
    resets() should be(3) // every actual fire really did call resetInMemoryState -- no phantom counting
  }

  it should "stop calling resetInMemoryState/clearLock at all once suspended, even though check() keeps being called" in {
    val (wd, resets) = newWatchdog(threshold = 2, maxConsecutiveRecoveries = 2)
    (1 to 30).foreach { _ => wd.check() }
    wd.isAutoRecoverySuspended should be(true)
    val resetsAtSuspension     = resets()
    val recoveriesAtSuspension = wd.totalRecoveries

    // Drive MANY more ticks past suspension -- if suspension didn't actually stop the recovery action,
    // resets()/totalRecoveries would keep climbing.
    (1 to 200).foreach { _ => wd.check() should be(false) } // never fires again once suspended
    resets() should be(resetsAtSuspension)
    wd.totalRecoveries should be(recoveriesAtSuspension)
  }

  "real progress after a recovery" should "fully reset the backoff/suspension state, so a LATER unrelated wedge starts fresh" in {
    val (wd, resets) = newWatchdog(threshold = 2, maxConsecutiveRecoveries = 2)

    // Drive to the very edge of suspension: one ineffective recovery, then progress arrives (the
    // recovery WORKED this time) before the second one would be judged.
    wd.check() should be(false) // tick 1, stalled
    wd.check() should be(true)  // tick 2, fires (attempt 1)
    wd.recordProgress()         // the recovery worked -- real progress observed on the very next tick
    wd.check() should be(false) // progress consumed here; backoff/suspension state resets to a clean slate
    wd.isAutoRecoverySuspended should be(false)

    // A LATER, completely unrelated wedge must get the ORIGINAL threshold again (2), not an inherited
    // doubled one, and a fresh consecutive-failure budget.
    wd.check() should be(false) // stalled tick 1 (fresh)
    wd.check() should be(true)  // stalled tick 2 (fresh) -- fires at the ORIGINAL threshold, not doubled
    resets() should be(2)
  }

  "totalRecoveries" should "count only genuinely fired recoveries, never the post-suspension no-ops" in {
    val (wd, _) = newWatchdog(threshold = 1, maxConsecutiveRecoveries = 3)
    (1 to 50).foreach { _ => wd.check() }
    wd.isAutoRecoverySuspended should be(true)
    wd.totalRecoveries should be(3L) // exactly maxConsecutiveRecoveries -- the cap, not the tick count
  }
}
