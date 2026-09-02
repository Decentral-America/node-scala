package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsSignature, BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffVote, Message, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

import java.nio.file.{Files, Paths}

/** Review fix (Critical, post-initial-landing): regression coverage for the bug the reviewer caught --
  * `HotStuffCoordinator.Enabled.applyQC` fires `onAction` for EVERY element of a QC's `actions`, and a QC
  * that fails epoch/crypto verification produces a LONE `HotStuffAction.Rejected(...)` (see
  * `HotStuffEngine.onQC`). The original wiring (`onAction = _ => hsWatchdog.recordProgress()`, in
  * `Application.scala` and both `HotStuffWatchdogDstReproductionSpecification` scenarios) treated THAT as
  * progress too, so a wedged replica that keeps receiving/self-forming rejected QCs (e.g. a stale
  * committee epoch that will never again match what this replica believes is current) would have its
  * stall counter reset every single tick and the watchdog would NEVER fire -- despite genuinely zero real
  * progress. This is precisely the failure mode Task 4 exists to catch.
  *
  * Fix: `onAction` must filter out `Rejected` before calling `recordProgress()` -- see the corrected
  * wiring in `Application.scala` (`hsOnAction`) and both DST scenario files. This spec proves the fix two
  * ways:
  *   1. With the FIXED filter, a `Rejected`-only QC stream does NOT mask the wedge: the watchdog still
  *      fires after `stallThreshold` ticks.
  *   2. With the ORIGINAL (buggy) unfiltered wiring, reproduced here deliberately as a negative control,
  *      the SAME `Rejected`-only stream DOES mask the wedge: the watchdog never fires, confirming this
  *      test would have caught the original bug had it existed before the fix landed.
  */
class HotStuffWatchdogRejectedStreamSpecification extends FlatSpec {
  private val kps                     = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val B: BlockId = ByteStr(Array.fill[Byte](32)(7))

  private def tempLockPath() = {
    val dir = Files.createTempDirectory("hotstuff-watchdog-rejected-stream-spec")
    dir.toFile.deleteOnExit()
    Paths.get(dir.toString, "locked-qc.dat")
  }

  /** A REAL, cryptographically valid QC (3-of-4 signers, genuinely forms and would `verifyQC` cleanly) --
    * but signed under `committeeEpoch = 99`, a value the coordinator constructed below (whose
    * `committeeEpochProvider` always reports `0`) will NEVER accept via
    * `HotStuffQuorum.acceptableCommitteeEpoch(99, 0)` (accepts only `0` or `-1`). Feeding this QC via
    * `onQC` therefore ALWAYS produces exactly `Seq(HotStuffAction.Rejected(...))` -- real network
    * traffic, real verification work, zero genuine progress -- precisely the case the fix targets.
    */
  private def perpetuallyRejectedQC(view: Int, height: Int): QuorumCertificate = {
    val msg   = HotStuffQuorum.voteMessage(view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, B, height, committeeEpoch = 99)
    val votes = (0 to 2).map(i =>
      HotStuffVote(view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, B, Height(height), i, kps(i).sign(msg, BlsUtils.BlsDomainSeparationTag).byteStr, committeeEpoch = 99)
    )
    HotStuffQuorum.formQC(votes, committee).toOption.get
  }

  private class SilentEffects extends HotStuffEffects {
    def broadcast(m: Message): Unit                                = ()
    def myVoterIndexes: Set[Int]                                   = Set.empty // never this replica's own turn -- purely a receiver in this spec
    def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = None
    def onCommit(blockId: BlockId, height: Int): Unit              = ()
    def onEquivocation(proof: HotStuffEquivocationProof): Unit     = ()
  }

  "a Rejected-only QC stream, with the FIXED onAction filter" should
    "NOT mask the wedge -- the watchdog still fires after stallThreshold ticks" in {
      val coordinator = new HotStuffCoordinator.Enabled(
        committeeProvider = () => committee,
        effects = new SilentEffects,
        extendsBranch = (_, _) => true,
        committeeEpochProvider = () => 0 // this replica believes epoch 0 is current, forever
      )
      val watchdog = new HotStuffWatchdog(
        committeeNonEmpty = () => committee.nonEmpty,
        lockPath = tempLockPath(),
        resetInMemoryState = () => coordinator.resetLocalSafetyState(),
        stallThreshold = 5
      )
      // THE FIX under test: filter out Rejected before it reaches recordProgress -- exactly
      // Application.scala's `hsOnAction`.
      val fixedOnAction: HotStuffAction => Unit = {
        case _: HotStuffAction.Rejected => ()
        case _                          => watchdog.recordProgress()
      }

      var firedAtTick: Option[Int] = None
      (1 to 10).foreach { tick =>
        // Each tick: a real round-timer tick (establishes/advances the pacemaker baseline, mirroring
        // production), PLUS delivery of a perpetually-rejected QC (mirroring a wedged replica that keeps
        // receiving/self-forming QCs it can never accept) via `onQC`, with the FIXED filter wired to it.
        coordinator.onRoundTimerTick()
        val (_, actions) = HotStuffEngine.onQC(EngineStateOf(coordinator), perpetuallyRejectedQC(view = tick, height = 100 + tick))
        actions.foreach(fixedOnAction) // simulate applyQC's onAction.foreach(action) loop with the real filter
        actions.forall { case _: HotStuffAction.Rejected => true; case _ => false } should be(true) // sanity: genuinely Rejected-only
        val fired = watchdog.check()
        if (fired && firedAtTick.isEmpty) firedAtTick = Some(tick)
      }

      firedAtTick should be(Some(5)) // the fix works: Rejected never resets the counter, so it still fires at N
      watchdog.totalRecoveries should be(2L) // fires at tick 5 and tick 10 (counter resets after each firing)
    }

  it should "also be proven directly through the real onQC entry point (not the extracted actions above), for the SAME Rejected-only stream" in {
    // Stronger version of the test above: drives the REAL public `coordinator.onQC(qc)` entry point
    // (exactly what `Application.scala`'s message-observer calls) instead of extracting `actions` via
    // `HotStuffEngine.onQC` directly, so this exercises the actual `applyQC`/`onAction` wiring inside
    // `HotStuffCoordinator.Enabled`, not just the pure reducer in isolation.
    var recordedProgress = 0
    val coordinator       = new HotStuffCoordinator.Enabled(
      committeeProvider = () => committee,
      effects = new SilentEffects,
      extendsBranch = (_, _) => true,
      committeeEpochProvider = () => 0,
      onAction = {
        case _: HotStuffAction.Rejected => ()
        case _                          => recordedProgress += 1
      }
    )

    (1 to 5).foreach(tick => coordinator.onQC(perpetuallyRejectedQC(view = tick, height = 100 + tick)))

    recordedProgress should be(0) // every one of the 5 QCs was rejected -- none should have counted as progress
  }

  "the SAME Rejected-only QC stream, with the ORIGINAL (unfiltered) wiring" should
    "mask the wedge -- negative control proving this test would have caught the original bug" in {
      val coordinator = new HotStuffCoordinator.Enabled(
        committeeProvider = () => committee,
        effects = new SilentEffects,
        extendsBranch = (_, _) => true,
        committeeEpochProvider = () => 0
      )
      val watchdog = new HotStuffWatchdog(
        committeeNonEmpty = () => committee.nonEmpty,
        lockPath = tempLockPath(),
        resetInMemoryState = () => coordinator.resetLocalSafetyState(),
        stallThreshold = 5
      )
      // THE ORIGINAL BUG, reproduced deliberately: no filter at all -- any action, Rejected included,
      // resets the watchdog's counter.
      val buggyOnAction: HotStuffAction => Unit = _ => watchdog.recordProgress()

      (1 to 20).foreach { tick =>
        coordinator.onRoundTimerTick()
        val (_, actions) = HotStuffEngine.onQC(EngineStateOf(coordinator), perpetuallyRejectedQC(view = tick, height = 100 + tick))
        actions.foreach(buggyOnAction)
        watchdog.check()
      }

      watchdog.totalRecoveries should be(0L) // BUG reproduced: the watchdog never fires despite 20 stalled ticks
    }

  /** Test-only helper: extracts a fresh `EngineState` matching `coordinator`'s current public view/committee,
    * for use ONLY with the pure `HotStuffEngine.onQC` reducer directly (not touching `coordinator`'s own
    * private state) -- needed because `HotStuffEngine.onQC`'s epoch-gating rejection is what this spec
    * needs to trigger deterministically without also depending on `applyQC`'s other side effects for the
    * first two tests. Constructed independently (not reflection into `coordinator`), using only
    * `coordinator`'s PUBLIC `currentView` and the same committee/epoch this spec already fixes at `0`.
    */
  private def EngineStateOf(coordinator: HotStuffCoordinator.Enabled): EngineState =
    EngineState(committee, pacemaker = PacemakerState(view = coordinator.currentView), committeeEpoch = 0)
}
