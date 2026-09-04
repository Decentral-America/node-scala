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
  *   - `committeeNonEmpty: () => Boolean` -- reports whether the SAME committee source the coordinator
  *     itself reads (`Application.scala` wires both to `blockchainUpdater.currentCommittedGeneratorSet`,
  *     via a thin `.nonEmpty` projection) is currently non-empty. If it reports `false`, the stall is a
  *     data-availability gap (no `CommitToGenerationTransaction` landed yet for this period), NOT a wedge
  *     -- see `DstEmptyCommitteeSourceScenarioSpecification` step 2: an empty committee cannot even
  *     accumulate a valid vote (`HotStuffQuorum`/`HotStuffVotePool.onVote` require the voter to be found
  *     in the LIVE committee), so "no progress" here is the CORRECT, safe outcome, not a bug -- and Task
  *     1's `dcc_hotstuff_lag`/absence alerting already covers this case separately. Wiping the lock file
  *     or resetting local safety state would do NOTHING to fix an empty committee (there is no committee
  *     to vote with) and would be actively harmful once a real committee DOES return, by discarding a
  *     lock that might otherwise still be safely reusable. So: empty committee => the stall counter is
  *     reset to zero and the watchdog stays silent, every single tick, for as long as the committee stays
  *     empty. It only starts counting once the committee is genuinely non-empty.
  *
  *     DELIBERATELY typed as `() => Boolean`, NOT `() => GeneratorSet`/`() => Seq[?]` (see the review fix
  *     this narrowing addresses, below): the watchdog has no legitimate use for the committee's actual
  *     contents, only whether it is empty, so the constructor accepts exactly that -- nothing wider.
  *
  *   - a progress signal wired to `HotStuffCoordinator.Enabled`'s additive `onAction` hook (see that
  *     class's doc for exactly which actions count as "progress" and why the bare-timeout `EnteredView`
  *     that fires on every stalled tick, AND a `Rejected` action, deliberately do NOT count -- the latter
  *     was a review-caught bug: an unfiltered `onAction = _ => recordProgress()` would let a
  *     perpetually-rejected QC stream, e.g. one signed under a stale committee epoch, mask a genuine
  *     wedge forever; see `HotStuffWatchdogRejectedStreamSpecification`). `recordProgress()` is called
  *     from that hook; `check()` consumes and resets that flag on every call.
  *
  * A SECOND, COMMIT-SPECIFIC SIGNAL (audit F-5, MEDIUM): `recordProgress()` above resets the view-based
  * counter for `Committed` AND `EnteredView` actions alike, so a replica that keeps forming/receiving
  * valid PREPARE/PRE_COMMIT QCs -- advancing the view every round -- but never reaches a COMMIT QC
  * calls `recordProgress()` on essentially every tick and the view-based counter never accumulates,
  * even though `hotStuffFinalizedHeight` is genuinely frozen. That is a plausible description of the
  * live incident this watchdog was built for (2h19m / 604+ blocks frozen while views kept advancing).
  * `recordCommit()` drives a SEPARATE, independent counter (`consecutiveTicksSinceCommit`) reset ONLY
  * by a genuine `HotStuffAction.Committed`, with its own (larger -- see `commitStallThreshold`)
  * threshold; `check()` fires recovery when EITHER counter trips. This widening is safe ONLY because
  * F-2 is already fixed (`resetLocalSafetyState()` preserves `lastVotedView`, the sole anti-double-vote
  * bound on the PREPARE path -- see that method's doc): firing recovery in strictly MORE situations
  * would otherwise have been a net safety regression, which is exactly why F-5's own recommendation
  * says this must be paired with F-2's fix, not shipped alone.
  *
  * NO DIRECT REFERENCE TO `finalizedHeight` -- the hard, non-negotiable constraint (task brief, "the
  * hard safety constraint"), SCOPED PRECISELY per audit F-4 (2026-08-31): this class holds no DIRECT
  * reference of any kind to `finalizedHeight`, `BlockchainUpdaterImpl`, feature-25's finality path, or
  * any component that could reach them -- and, following a review finding, this is now enforced by the
  * TYPE SIGNATURE itself, not merely by this class's code happening not to call anything else on a
  * wider object it was handed. Its constructor accepts exactly four collaborators -- a `() => Boolean`
  * committee-non-emptiness check, a progress accessor/reset, a `clearLock: Path => Unit` action, and a
  * `path: Path` to the on-disk lock file. `Boolean`, `Unit`, and `Path` are the ENTIRE capability
  * surface available to this class; even a maximally buggy implementation of `check()` could not
  * synthesize a `BlockchainUpdaterImpl`/`finalizedHeight` reference out of a `Boolean` and a `Path`,
  * because none was ever received. (Earlier revision: the committee parameter was `() => Seq[?]` /
  * `() => GeneratorSet`, which in PRODUCTION closes over `blockchainUpdater` to compute -- the closure
  * itself could reach `blockchainUpdater`'s other methods even though this class's code never called
  * them. The type is now narrowed so that possibility doesn't exist at all, not just "isn't exercised".)
  * `HotStuffWatchdogFinalizedHeightIsolationSpecification` asserts this property directly (not just by
  * comment) by constructing a `finalizedHeight` variable that ONLY a distinct, never-invoked function
  * could mutate, and proving the watchdog's full recovery action leaves it untouched.
  *
  * WHAT THIS DOES NOT PROVE (audit F-4, INFO -- read this before assuming "cannot touch finalized
  * state"): the property proven above is "this class holds no DIRECT reference through which it could
  * write `finalizedHeight`". It is NOT a proof that the watchdog cannot INFLUENCE finalized state, and
  * an earlier version of this comment read as though it were -- that was an overclaim, corrected here.
  * The indirect path is real and complete: this watchdog's recovery action resets safety state
  * (`resetLocalSafetyState()`) -> safety state governs voting (`HotStuffSafety.safeToVote`) -> voting
  * governs which QCs form -> a COMMIT-phase QC calls `effects.onCommit` -> under
  * `dcc.hotstuff.authoritative = true`, `onCommit` calls `raiseFinalizedHeight`
  * (`NodeHotStuffEffects.scala`). Audit finding F-2 is precisely an instance of this indirect path (a
  * watchdog-driven reset enabling an equivocating double-vote); see F-2 for the concrete mechanism, and
  * F-1 for what the raised `finalizedHeight` value is actually worth once written (advisory, not
  * enforcing -- see `HotStuffSettings.authoritative`'s doc). This class's own guarantee stops at "no
  * direct reference" and was never meant to substitute for reasoning about that longer causal chain.
  *
  * N SIZING (do not guess -- see the task's real numbers):
  *   - `round-timeout = 1200ms` (`node-config/testnet/dcc.conf`).
  *   - Task 5's `DstEmptyCommitteeSourceScenarioSpecification` found that even with a REAL committee
  *     restored, self-resumption within 10 `tickTimeoutAll` rounds is FLAKY: only 49/100 seeds (49%)
  *     converge to a full commit in that window; 51% do not, with nothing structurally different between
  *     seeds besides delivery-delay timing jitter. So firing a destructive reset too early after a real
  *     committee returns risks aborting a legitimate self-recovery that was already converging on its
  *     own, discarding real (still possibly reusable) lock state for no reason.
  *   - REVIEW-CORRECTED JUSTIFICATION (the original doc here compared incommensurable units -- Task 5's
  *     "10" is 10 SIMULATED `DstHarness` rounds, each drained via `run(maxEvents=50)` against a 1-3ms
  *     simulated network delay, i.e. a budget of simulated message-passing opportunity, NOT a wall-clock
  *     duration; `N=60` is 60 REAL 1200ms scheduler ticks = 72 REAL seconds. Dividing one by the other to
  *     claim "6x" was not a valid comparison, even though the conclusion below still holds). The two
  *     arguments that actually DO hold:
  *     (a) 72 real seconds at a 1200ms round-timeout is many real HotStuff rounds' worth of wall-clock
  *         opportunity for self-resumption to complete -- far more real time than Task 5's simulated
  *         10-round budget represents, even though the units don't convert directly (simulated rounds
  *         with near-zero network delay are not directly comparable to real scheduler ticks).
  *     (b) The empty-committee reset in `check()` below (`if (!committeeNonEmpty()) { ... reset to 0
  *         ... }`) means the stall counter CANNOT inherit "stall credit" from Task 5's starvation window:
  *         it is hard-reset to zero on every tick where the committee is empty, so it only starts
  *         counting from the EXACT moment a real committee returns -- precisely when self-resumption
  *         becomes possible. This guarantees self-resumption always gets a full, fresh 72-second window,
  *         never a partial one eaten by however long the prior starvation lasted. This property is
  *         directly tested: `HotStuffWatchdogSpecification`'s "should reset the stall counter to zero
  *         while the committee is empty, so ticks accumulated before emptiness don't carry over" test.
  *   - Also staying far below Task 1's `HotStuffLagGrowing`/`HotStuffMetricMissing` alert windows
  *     (30m/15m) -- so the watchdog gets a real chance to fix the wedge automatically, and testnet
  *     operators see at most roughly a minute of extra stall beyond a genuine transient hiccup, long
  *     before a human page would otherwise be the only recourse. This is testnet: minutes of stall is
  *     tolerable, hours (604+ blocks / 2h19m+, Task 2's benchmark) is not.
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
  *
  * BACKOFF AND A HARD CAP (review finding, post-initial-landing): without this, a wedge the recovery
  * action genuinely cannot fix (e.g. a correlated fault -- a committee-epoch skew wedging several
  * replicas at once, where clearing one replica's OWN local lock changes nothing about why quorum can't
  * form) would cause this watchdog to blank local safety state every `stallThreshold` ticks, FOREVER,
  * with no escalation and no human ever paged -- "fails safe but silently noisy forever" is not an
  * acceptable unattended-operation posture for something whose whole point is running unattended. Two
  * changes address this:
  *   1. After each fired recovery that does NOT resolve the stall (progress still absent
  *      `stallThreshold` ticks later), the EFFECTIVE threshold for the next attempt DOUBLES
  *      (`effectiveThreshold *= 2`, uncapped in magnitude but bounded in attempt COUNT -- see next
  *      point) -- so repeated firing against an unfixable wedge spaces itself out exponentially rather
  *      than hammering the same ineffective action on a fixed cadence forever.
  *   2. After `MaxConsecutiveRecoveries` (default 5) recoveries IN A ROW have each been followed by
  *      another full stall with zero progress (i.e. the recovery action provably did not help, 5 times
  *      running), this watchdog STOPS auto-recovering entirely and only logs loudly every tick from then
  *      on. By the time 5 exponentially-spaced attempts have all failed, Task 1's `HotStuffLagGrowing`
  *      (30m)/`HotStuffMetricMissing` (15m) alerts will already be firing on any real deployment at this
  *      stall's likely duration -- so a human gets paged instead of the node silently self-resetting on
  *      an unbounded cadence forever. `totalRecoveries` still only counts ACTUAL recovery actions fired
  *      (never the post-cap no-ops), so it remains an accurate "how many times did this genuinely act"
  *      counter for tests/observability. The counter resets to a clean slate (both the exponential
  *      threshold and the consecutive-failure count) the moment real progress is next observed --  a
  *      wedge that WAS genuinely fixed (by this watchdog or otherwise) does not permanently exhaust the
  *      budget for a LATER, unrelated wedge.
  */
final class HotStuffWatchdog(
    committeeNonEmpty: () => Boolean,
    lockPath: Path,
    resetInMemoryState: () => Unit,
    clearLock: Path => Unit = HotStuffWatchdog.deleteQuietly,
    stallThreshold: Int = HotStuffWatchdog.DefaultStallThreshold,
    maxConsecutiveRecoveries: Int = HotStuffWatchdog.MaxConsecutiveRecoveries,
    // audit F-5 (MEDIUM): a SECOND, commit-specific staleness counter, independent of the view-based
    // `consecutiveStalledTicks` below. `recordProgress()` (any verified/accepted QC action -- see its
    // doc) resets the view-based counter but NOT this one; only `recordCommit()` (a genuine
    // `HotStuffAction.Committed`) resets THIS counter. See `check()` for why this closes the gap: a
    // replica that keeps forming/receiving valid PREPARE/PRE_COMMIT QCs -- advancing the view every
    // round -- but never reaches a COMMIT QC calls `recordProgress()` on essentially every tick, so
    // `consecutiveStalledTicks` never accumulates and the view-based detector never fires, even though
    // `hotStuffFinalizedHeight` is genuinely frozen. That is a plausible description of the live
    // incident this watchdog was built for (2h19m / 604+ blocks frozen while views kept advancing) --
    // "frozen finalized height" means no COMMIT QCs, which does NOT imply no PREPARE/PRE_COMMIT QCs.
    //
    // Safe to widen the trigger this way ONLY because F-2 is already fixed:
    // `resetLocalSafetyState()` preserves `lastVotedView` (the only anti-double-vote bound on the
    // PREPARE path -- see that method's doc and `HotStuffResetDoubleVoteSpecification`), so firing
    // recovery in MORE situations no longer risks reopening the double-vote window F-2 closed. Before
    // that fix, widening the trigger here would have been a net safety regression (more resets = more
    // chances to hit the F-2 window) -- the audit explicitly flags this ordering requirement (F-5's
    // recommendation: "this must be paired with F-2's fix").
    //
    // Meaningfully LARGER than `stallThreshold`: commits legitimately lag views by design (a block only
    // commits on the THIRD phase's QC -- PREPARE -> PRE_COMMIT -> COMMIT -- so even perfectly healthy
    // operation has `Committed` actions arrive less often than `EnteredView` ones, and normal network/
    // committee churn adds further lag on top). Defaults to `3x stallThreshold` (180 ticks = 216
    // real seconds at the 1200ms round-timeout default) -- generous enough that ordinary 3-phase lag
    // and transient hiccups never spuriously fire this counter, while still firing well inside Task 1's
    // 15m/30m alert windows for a genuine commit-only stall.
    commitStallThreshold: Int = HotStuffWatchdog.DefaultCommitStallThreshold
) extends StrictLogging {
  require(stallThreshold > 0, "HotStuffWatchdog stallThreshold must be positive")
  require(maxConsecutiveRecoveries > 0, "HotStuffWatchdog maxConsecutiveRecoveries must be positive")
  require(commitStallThreshold > 0, "HotStuffWatchdog commitStallThreshold must be positive")

  private var consecutiveStalledTicks: Int    = 0
  private var progressSinceLastCheck: Boolean = false
  private var recoveryCount: Long             = 0L

  // audit F-5: commit-specific staleness. `consecutiveTicksSinceCommit` counts ticks since the last
  // `recordCommit()` call (or since construction); `commitSinceLastCheck` is the per-tick flag
  // `check()` consumes, mirroring `progressSinceLastCheck`'s own consume-on-check pattern below.
  private var consecutiveTicksSinceCommit: Int = 0
  private var commitSinceLastCheck: Boolean    = false

  // Backoff state (review fix): `effectiveThreshold` is the ticks-to-fire for the NEXT attempt --
  // starts at `stallThreshold`, doubles after each recovery that turns out not to have helped.
  // `consecutiveIneffectiveRecoveries` counts recoveries in a row that were each followed by another
  // full stall with zero progress; once it reaches `maxConsecutiveRecoveries`, auto-recovery is
  // permanently suspended (this run) until real progress is next observed.
  private var effectiveThreshold: Long              = stallThreshold.toLong
  private var consecutiveIneffectiveRecoveries: Int = 0
  private var awaitingResultOfLastRecovery: Boolean = false
  private var autoRecoverySuspended: Boolean        = false

  /** Wire to `HotStuffCoordinator.Enabled`'s `onAction` hook: any action reaching here means a QC was
    * genuinely verified/accepted this tick -- real progress, not a bare-timeout view bump. Safe to call
    * from the same single thread as `check()`; NOT synchronized (matches the coordinator's own threading
    * contract).
    */
  def recordProgress(): Unit = progressSinceLastCheck = true

  /** Wire to `HotStuffCoordinator.Enabled`'s `onAction` hook, but ONLY for a genuine
    * `HotStuffAction.Committed` (audit F-5) -- distinct from `recordProgress()`, which fires for
    * `Committed` AND `EnteredView` alike. `Application.scala`'s `hsOnAction` calls BOTH
    * `recordProgress()` and (for `Committed` specifically) this method on the same action, so a commit
    * resets both counters; a bare view-advance (`EnteredView` from a verified QC, or the unconditional
    * bare-timeout `EnteredView`) resets only the view-based one. Safe to call from the same single
    * thread as `check()`; NOT synchronized (matches the coordinator's own threading contract).
    */
  def recordCommit(): Unit = commitSinceLastCheck = true

  /** How many recovery actions this watchdog has fired since construction (observability/testing). Only
    * counts ACTUAL fired recoveries -- never the logged-only no-ops after `autoRecoverySuspended`.
    */
  def totalRecoveries: Long = recoveryCount

  /** True once `maxConsecutiveRecoveries` recoveries in a row have each failed to produce progress
    * before the next full stall -- this watchdog will not fire again (only log) until real progress is
    * observed. Exposed for tests/observability.
    */
  def isAutoRecoverySuspended: Boolean = autoRecoverySuspended

  /** Call once per round-timer tick, on the SAME thread as `onRoundTimerTick()`. Advances the stall
    * counter and fires recovery exactly when the wedge signature is met. Returns `true` iff a recovery
    * action fired this call (test/observability convenience) -- `false` both when nothing fired AND when
    * the wedge signature was met but auto-recovery is currently suspended (see class doc).
    */
  def check(): Boolean = {
    if (!committeeNonEmpty()) {
      // Data-availability gap, not a wedge (see class doc). Do not accumulate stall count against it,
      // and do not let a progress flag earned just before the committee emptied leak into a later,
      // unrelated non-empty-committee stall window.
      consecutiveStalledTicks = 0
      progressSinceLastCheck = false
      consecutiveTicksSinceCommit = 0
      commitSinceLastCheck = false
      false
    } else {
      // audit F-5: advance/reset the commit-specific counter FIRST and independently of the view-based
      // branch below -- a `Committed` action also always implies `recordProgress()` fired (see
      // `recordCommit`'s doc), but the reverse is not true, so this counter must be driven by its own
      // flag, not derived from `progressSinceLastCheck`.
      if (commitSinceLastCheck) {
        consecutiveTicksSinceCommit = 0
        commitSinceLastCheck = false
      } else {
        consecutiveTicksSinceCommit += 1
      }
      val commitStalled = consecutiveTicksSinceCommit >= commitStallThreshold

      if (progressSinceLastCheck) {
        // Real progress happened since the last check (view-based signal) -- but per F-5, that alone no
        // longer guarantees health: `commitStalled` may still independently be true (views advancing,
        // nothing committing). If EITHER signal demands recovery, fire it; otherwise this is a genuinely
        // healthy tick and the recovery/backoff state resets exactly as before F-5.
        if (!commitStalled) {
          // Committee is healthy on BOTH signals. If this followed a recovery attempt, that attempt
          // WORKED: reset all backoff state to a clean slate so a later, unrelated wedge starts fresh
          // rather than inheriting an escalated threshold/near-exhausted budget.
          if (awaitingResultOfLastRecovery) {
            effectiveThreshold = stallThreshold.toLong
            consecutiveIneffectiveRecoveries = 0
            autoRecoverySuspended = false
            awaitingResultOfLastRecovery = false
          }
          consecutiveStalledTicks = 0
          progressSinceLastCheck = false
          false
        } else {
          fireForWedge(consecutiveTicksSinceCommit, "commit-specific stall (views advancing, nothing committing -- audit F-5)")
        }
      } else {
        consecutiveStalledTicks += 1
        val viewStalled = consecutiveStalledTicks >= effectiveThreshold
        if (viewStalled || commitStalled) {
          val ticksElapsed = if (viewStalled) consecutiveStalledTicks else consecutiveTicksSinceCommit
          val reason       = if (viewStalled) "zero progress despite a non-empty committee" else "commit-specific stall (audit F-5)"
          fireForWedge(ticksElapsed, reason)
        } else false
      }
    }
  }

  /** Shared recovery-firing path for BOTH the view-based and commit-specific (audit F-5) wedge
    * signatures -- the exponential-backoff / consecutive-failure-suspension state machine (see class
    * doc's "BACKOFF AND A HARD CAP") is a property of "how many times has recovery been tried without
    * working", not of which signal tripped it, so both signals drive the SAME budget rather than each
    * getting an independent one (which would let a wedge that trips both alternately dodge suspension
    * forever). Resets BOTH counters on firing, so neither signal double-fires on the very next tick
    * against a target the recovery action has already reset local state for.
    */
  private def fireForWedge(ticksElapsed: Long, reason: String): Boolean = {
    consecutiveStalledTicks = 0
    consecutiveTicksSinceCommit = 0
    if (awaitingResultOfLastRecovery) {
      // The PREVIOUS recovery did not help: another full (escalated) stall elapsed with zero progress.
      consecutiveIneffectiveRecoveries += 1
      if (consecutiveIneffectiveRecoveries >= maxConsecutiveRecoveries) {
        autoRecoverySuspended = true
        logger.warn(
          s"[HotStuff] WATCHDOG: $consecutiveIneffectiveRecoveries consecutive recovery attempts have " +
            "ALL failed to restore progress -- suspending further automated recovery (this run) to avoid " +
            "an unbounded reset loop; a human should investigate (Task 1's HotStuffLagGrowing/" +
            "HotStuffMetricMissing alerts should already be firing by now). Will resume auto-recovery " +
            "immediately once real progress is next observed."
        )
        false
      } else {
        effectiveThreshold *= 2 // exponential backoff before the next attempt
        fireRecovery(ticksElapsed, reason)
        true
      }
    } else {
      fireRecovery(ticksElapsed, reason)
      awaitingResultOfLastRecovery = true
      true
    }
  }

  private def fireRecovery(ticksElapsed: Long, reason: String): Unit = {
    logger.warn(
      s"[HotStuff] WATCHDOG: $ticksElapsed consecutive round-timer ticks -- $reason -- clearing persisted " +
        "lockedQC and resetting local safety state (automated recovery from the 2026-08-30/31 wedge; blast " +
        "radius is HotStuff's own local state only)" +
        (if (consecutiveIneffectiveRecoveries > 0) s" [attempt #${consecutiveIneffectiveRecoveries + 1} after backoff]" else "")
    )
    clearLock(lockPath)
    resetInMemoryState()
    recoveryCount += 1
  }
}

object HotStuffWatchdog extends StrictLogging {

  /** See this class's doc for the full sizing justification: 60 x round-timeout(1200ms) = 72 real
    * seconds -- many real HotStuff rounds' worth of wall-clock opportunity, and (more importantly) the
    * empty-committee reset guarantees this is always a FULL, FRESH window starting exactly when a real
    * committee returns, never a partial one inherited from `DstEmptyCommitteeSourceScenarioSpecification`'s
    * flaky (49/100) simulated starvation window -- while staying far below Task 1's 15m/30m alert
    * windows. (NOT "6x" Task 5's 10-round budget -- that comparison mixed simulated-round and
    * wall-clock-second units and has been corrected in this class's doc.)
    */
  val DefaultStallThreshold: Int = 60

  /** Review-fix default: after this many consecutive recoveries have EACH failed to produce progress
    * before the next (exponentially-backed-off) full stall, auto-recovery suspends itself (this run)
    * rather than resetting local safety state on an unbounded cadence forever against a wedge it
    * provably cannot fix (e.g. a correlated multi-replica fault). 5 was chosen so that suspension
    * itself lands at the 5th fire's tick (60+120+240+480+960 = 1920 ticks x 1200ms = 38.4 minutes,
    * since each gap between fires equals the current backed-off threshold) -- just past Task 1's
    * `HotStuffLagGrowing` alert window (30m), so a human is already being paged well before
    * auto-recovery gives up -- the watchdog hands off to the alert rather than either giving up long
    * before an alert would fire or continuing to act long after a human should have taken over.
    */
  val MaxConsecutiveRecoveries: Int = 5

  /** audit F-5 (MEDIUM): default for the commit-specific staleness threshold, `3x DefaultStallThreshold`
    * (180 ticks = 216 real seconds at the 1200ms round-timeout default). Deliberately larger than
    * `DefaultStallThreshold`: commits legitimately lag views (3-phase HotStuff commits only on the
    * THIRD QC of a round -- PREPARE -> PRE_COMMIT -> COMMIT -- so `Committed` actions are inherently
    * less frequent than `EnteredView` ones even under perfectly healthy operation), so this threshold
    * must tolerate that structural lag plus ordinary transient hiccups without spuriously firing, while
    * still catching a genuine commit-only stall (the audit's F-5 scenario: views advancing every round,
    * nothing ever reaching COMMIT) well inside Task 1's 15m/30m alert windows. See `HotStuffWatchdog`'s
    * constructor doc for why widening the trigger this way is safe only now that F-2
    * (`resetLocalSafetyState` preserving `lastVotedView`) is already fixed.
    */
  val DefaultCommitStallThreshold: Int = DefaultStallThreshold * 3

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
