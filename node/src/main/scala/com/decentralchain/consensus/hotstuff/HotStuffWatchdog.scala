package com.decentralchain.consensus.hotstuff

import com.typesafe.scalalogging.StrictLogging

import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** Automates today's real manual incident recovery (2026-08-30/31, see
  * `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` and `HotStuffCoordinator.Enabled`'s `resetLocalSafetyState`
  * doc): notice the round-timer ticking with zero HotStuff progress despite a real, non-empty committee,
  * clear the stale persisted lock, and force a fresh local safety slate + re-entry at the current view --
  * without a human SSHing in.
  *
  * THE WEDGE SIGNATURE (exactly, per the task brief): `onRoundTimerTick` fires every `round-timeout` with
  * zero `Committed`/accepted-QC `EnteredView` action for N consecutive ticks, DESPITE a non-empty current
  * committee. This is fed by two independent inputs, both read fresh on every `check()` call:
  *
  *   - `committeeProvider: () => GeneratorSet` -- the SAME committee source the coordinator itself reads
  *     (`Application.scala` wires both to `blockchainUpdater.currentCommittedGeneratorSet`). If this is
  *     EMPTY, the stall is a data-availability gap (no `CommitToGenerationTransaction` landed yet for
  *     this period), NOT a wedge -- see `DstEmptyCommitteeSourceScenarioSpecification` step 2: an empty
  *     committee cannot even accumulate a valid vote (`HotStuffQuorum`/`HotStuffVotePool.onVote` require
  *     the voter to be found in the LIVE committee), so "no progress" here is the CORRECT, safe outcome,
  *     not a bug -- and Task 1's `dcc_hotstuff_lag`/absence alerting already covers this case separately.
  *     Wiping the lock file or resetting local safety state would do NOTHING to fix an empty committee
  *     (there is no committee to vote with) and would be actively harmful once a real committee DOES
  *     return, by discarding a lock that might otherwise still be safely reusable. So: empty committee =>
  *     the stall counter is reset to zero and the watchdog stays silent, every single tick, for as long as
  *     the committee stays empty. It only starts counting once the committee is genuinely non-empty.
  *
  *   - a progress signal wired to `HotStuffCoordinator.Enabled`'s additive `onAction` hook (see that
  *     class's doc for exactly which actions count as "progress" and why the bare-timeout `EnteredView`
  *     that fires on every stalled tick deliberately does NOT). `recordProgress()` is called from that
  *     hook; `wasProgressSinceLastCheck()` reports (and resets) whether any progress happened since the
  *     watchdog last checked.
  *
  * SAFETY BY CONSTRUCTION -- the hard, non-negotiable constraint (task brief, "the hard safety
  * constraint"): this class holds no reference of any kind to `finalizedHeight`, `BlockchainUpdaterImpl`,
  * feature-25's finality path, or any component that could reach them. Its constructor accepts exactly
  * four collaborators -- a committee accessor, a progress accessor/reset, a `clearLock: () => Unit`
  * action, and a `path: Path` to the on-disk lock file -- none of which is, wraps, or can reach the
  * authoritative finality machinery. `HotStuffWatchdogFinalizedHeightIsolationSpecification` asserts this
  * property directly (not just by comment) by constructing a `finalizedHeight` variable that ONLY a
  * distinct, never-invoked function could mutate, and proving the watchdog's full recovery action leaves
  * it untouched.
  *
  * N SIZING (do not guess -- see the task's real numbers):
  *   - `round-timeout = 1200ms` (`node-config/testnet/dcc.conf`).
  *   - Task 5's `DstEmptyCommitteeSourceScenarioSpecification` found that even with a REAL committee
  *     restored, self-resumption within 10 `tickTimeoutAll` rounds is FLAKY: only 49/100 seeds (49%)
  *     converge to a full commit in that window; 51% do not, with nothing structurally different between
  *     seeds besides delivery-delay timing jitter. So firing a destructive reset at or before 10
  *     consecutive stalled ticks risks aborting a legitimate self-recovery that was already converging on
  *     its own, discarding real (still possibly reusable) lock state for no reason.
  *   - `N = 60` consecutive stalled ticks (60 x 1200ms = 72s, ~1.2 minutes) was chosen as clearly longer
  *     than the 10-round self-resumption window Task 5 measured (6x that window, giving genuine
  *     self-recovery ample extra room to finish before the watchdog would ever preempt it) while staying
  *     far below Task 1's `HotStuffLagGrowing`/`HotStuffMetricMissing` alert windows (30m/15m) -- so the
  *     watchdog gets a real chance to fix the wedge automatically, and testnet operators see at most ~1-2
  *     minutes of extra stall beyond a genuine transient hiccup, long before a human page would otherwise
  *     be the only recourse. This is testnet: minutes of stall is tolerable, hours (604+ blocks / 2h19m+,
  *     Task 2's benchmark) is not.
  *   - Configurable via the constructor (not hardcoded) so a future soak can tune it with real data,
  *     defaulting to 60 to match this reasoning.
  *
  * RECOVERY ACTION, exactly the manual steps from the incident, automated:
  *   1. Delete the persisted `locked-qc.dat` (`clearLock` / the on-disk half).
  *   2. Reset the coordinator's in-memory safety state (`resetLocalSafetyState()`'s in-process half --
  *      see that method's doc; wired via `resetInMemoryState` here, not called directly on a concrete
  *      `HotStuffCoordinator.Enabled` reference so this class stays agnostic of the coordinator's own
  *      type and cannot accidentally be handed anything with a wider blast radius).
  *   3. Re-entry at the current view happens for free: `HotStuffCoordinator`'s every existing entry point
  *      (including the very next `onRoundTimerTick`) already calls `refreshCommittee()` at its top, so no
  *      separate "refresh" action is needed here -- the next tick naturally starts from a clean safety
  *      slate against a freshly-read committee.
  *
  * THREADING: like `HotStuffCoordinator.Enabled`, this class is NOT internally synchronized. `check()`
  * must be confined to the same single thread that drives `onRoundTimerTick()` (production:
  * `Application.scala`'s `hotStuffScheduler`) -- call it immediately after (or in place of a bare) each
  * `onRoundTimerTick()` invocation, in that same scheduled callback.
  */
final class HotStuffWatchdog(
    committeeProvider: () => Seq[?],
    clearLock: Path => Unit = HotStuffWatchdog.deleteQuietly,
    lockPath: Path,
    resetInMemoryState: () => Unit,
    stallThreshold: Int = HotStuffWatchdog.DefaultStallThreshold
) extends StrictLogging {
  require(stallThreshold > 0, "HotStuffWatchdog stallThreshold must be positive")

  private var consecutiveStalledTicks: Int    = 0
  private var progressSinceLastCheck: Boolean = false
  private var recoveryCount: Long             = 0L

  /** Wire to `HotStuffCoordinator.Enabled`'s `onAction` hook: any action reaching here means a QC was
    * genuinely verified/accepted this tick -- real progress, not a bare-timeout view bump. Safe to call
    * from the same single thread as `check()`; NOT synchronized (matches the coordinator's own threading
    * contract).
    */
  def recordProgress(): Unit = progressSinceLastCheck = true

  /** How many recovery actions this watchdog has fired since construction (observability/testing). */
  def totalRecoveries: Long = recoveryCount

  /** Call once per round-timer tick, on the SAME thread as `onRoundTimerTick()`. Advances the stall
    * counter and fires recovery exactly when the wedge signature is met. Returns `true` iff a recovery
    * action fired this call (test/observability convenience).
    */
  def check(): Boolean = {
    val committee = committeeProvider()
    if (committee.isEmpty) {
      // Data-availability gap, not a wedge (see class doc). Do not accumulate stall count against it,
      // and do not let a progress flag earned just before the committee emptied leak into a later,
      // unrelated non-empty-committee stall window.
      consecutiveStalledTicks = 0
      progressSinceLastCheck = false
      false
    } else if (progressSinceLastCheck) {
      // Real progress happened since the last check -- committee is healthy, reset the counter.
      consecutiveStalledTicks = 0
      progressSinceLastCheck = false
      false
    } else {
      consecutiveStalledTicks += 1
      if (consecutiveStalledTicks >= stallThreshold) {
        fireRecovery()
        consecutiveStalledTicks = 0
        true
      } else false
    }
  }

  private def fireRecovery(): Unit = {
    logger.warn(
      s"[HotStuff] WATCHDOG: $stallThreshold consecutive round-timer ticks with zero progress despite a " +
        "non-empty committee -- clearing persisted lockedQC and resetting local safety state (automated " +
        "recovery from the 2026-08-30/31 wedge; blast radius is HotStuff's own local state only)"
    )
    clearLock(lockPath)
    resetInMemoryState()
    recoveryCount += 1
  }
}

object HotStuffWatchdog extends StrictLogging {

  /** See this class's doc for the full sizing justification: 60 x round-timeout(1200ms) = 72s, ~6x
    * longer than the 10-round self-resumption window `DstEmptyCommitteeSourceScenarioSpecification`
    * measured as flaky (49/100), and far below Task 1's 15m/30m alert windows.
    */
  val DefaultStallThreshold: Int = 60

  /** Default `clearLock`: best-effort delete, never throws (mirrors `HotStuffLockedQCStore`'s own
    * failure-handling philosophy -- a failed delete just means the next successful watchdog cycle or
    * process restart tries again, never worse than today's fully-manual recovery).
    */
  def deleteQuietly(path: Path): Unit =
    try {
      Files.deleteIfExists(path)
      ()
    } catch {
      case NonFatal(e) =>
        logger.warn(s"[HotStuff] watchdog failed to delete $path (will retry on next recovery cycle): ${e.getMessage}")
    }
}
