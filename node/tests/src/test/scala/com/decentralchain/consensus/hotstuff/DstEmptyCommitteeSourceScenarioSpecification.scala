package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.sim.{DstHarness, FaultProfile, SafetyInvariants}
import com.decentralchain.crypto.bls.TestBlsKeyPair
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}
import com.decentralchain.test.FlatSpec

/** DST scenario for TODAY'S live incident class (confirmed on the production testnet node,
  * 2026-08-31, via direct polling: `hotStuffFinalizedHeight` stuck at 197 for 30+ minutes while
  * `blockchainHeight` climbed past 568). Unlike `DstCommitteeChangeScenarioSpecification` (which
  * models the committee's STAKE/COMPOSITION changing mid-round -- still a non-empty `GeneratorSet` at
  * every instant) and `DstCommitteeEpochRotationScenarioSpecification` (which models the committee
  * EPOCH advancing), this models the committee DATA SOURCE ITSELF going empty: a period boundary
  * where no `CommitToGenerationTransaction` landed in time, so `committeeProvider()` legitimately
  * returns `Seq.empty` for a while -- a data-availability failure, not a network fault or a
  * composition change. `DstHarness.setCommittee` already supports injecting this directly; no new
  * harness infrastructure is needed.
  *
  * Sequence modeled:
  *   1. Committee starts non-empty (4 nodes, equal stake) -- a round proposed and driven to commit,
  *      confirming the harness/committee are healthy before the fault is injected.
  *   2. `setCommittee(Seq.empty)` -- simulates the period boundary with no committed-generators
  *      transaction landed yet. A new round is proposed and driven; per the code read backing this
  *      scenario (`HotStuffQuorum.verifyVote`/`HotStuffVotePool.onVote`: a vote's signer must be found
  *      in the LIVE committee via `committee.find(_.index.toInt == vote.voterIndex)`, so every vote is
  *      dropped on ingress against an empty committee -- `FinalizationVoting.isFinalized(0, 0)` being
  *      vacuously `true` is never reached because the empty bucket never even accumulates a valid
  *      vote), no QC can spuriously form: the round should simply fail to progress, not silently
  *      "succeed" with a false quorum.
  *   3. `setCommittee(<real committee>)` -- simulates the automation eventually running (this
  *      session's actual fix) and a fresh committee becoming available again.
  *
  * Two things are checked, matching the task brief precisely:
  *   (a) `SafetyInvariants.checkAll` holds THROUGHOUT (no fork, no regression) -- including during the
  *       empty-committee window. This is expected to hold given the vote-verification read above: an
  *       empty committee cannot produce a spurious commit to fork against or regress from.
  *   (b) The coordinator actually RESUMES COMMITTING once handed a real committee again -- not merely
  *       "stays safe while starved." This is the assertion today's real incident got wrong: production
  *       did NOT self-resume once the automation eventually ran; it required a full manual node
  *       restart.
  *
  *       MEASURED RESULT, UPDATED 2026-09-02 -- SELF-RESUMPTION IS NOW RELIABLE (100/100 seeds).
  *       This scenario was originally written against a measured 49/100 (flaky, coin-flip) and its band
  *       asserted that split. The audit F-9 fix (`voted`-set pruning in `HotStuffCoordinator.prunePool`,
  *       commit `f8c2650c52`) changed this to a deterministic 100/100. ATTRIBUTION (four runs of this
  *       exact scenario, see the hardening report):
  *         - clean tip `197420fd10` (pre-batch):                        49/100
  *         - full audit-hardening batch `66eb535158`:                   100/100
  *         - that batch with ONLY the F-9 coordinator change reverted:   49/100
  *         - F-9 restored:                                             100/100
  *       i.e. F-9 is the sole cause; no other item in the batch moves this number.
  *
  *       MECHANISM. `castVotes` is guarded by `voted`, a set of `(view, phase, blockId)` keys. During
  *       the empty-committee window this scenario drives ~10 `tickTimeoutAll()` rounds in which nodes
  *       DO reach `castVotes` and record `voted` entries, but every resulting vote is dropped on
  *       ingress (`HotStuffQuorum.verifyVote` cannot find the signer in an empty committee), so no QC
  *       ever forms for those targets. Before F-9, `voted` was never reclaimed, so those dead entries
  *       persisted indefinitely; whether a given node could then vote in the post-restoration round
  *       depended on whether timing jitter had happened to leave it holding a `voted` entry that
  *       collided with the recovery round's target -- which is exactly the observed coin-flip. F-9
  *       prunes `voted` on every `prunePool()` (reached from `onTimeout()`, itself reached from
  *       `onRoundTimerTick()` on each tick) under the same `view >= pacemaker.view - 1` margin the
  *       pool uses, so the stale entries age out as views advance during the starvation window and
  *       every node can vote cleanly once the real committee returns.
  *
  *       This CLOSES audit F-10 ("nobody has characterized why it is 49%"): the 49% was not inherent
  *       timing nondeterminism in HotStuff recovery, it was the unbounded `voted` set retaining dead
  *       vote-guard entries across a starvation window. The live 2026-08-30/31 incident (which needed
  *       a manual restart) is consistent with the pre-fix "did not resume" half; a restart cleared the
  *       in-memory `voted` set, which is precisely what F-9 now does incrementally.
  *
  * Scope, stated honestly (matching `DstCommitteeChangeScenarioSpecification`'s corrected docstring
  * standard): this harness only ever injects ONE candidate `BlockId` per round via `leaderTurn`, so
  * `SafetyInvariants.noFork` has nothing to compare against and is structurally vacuous here for fork
  * detection, exactly as documented on `DstCommitteeChangeScenarioSpecification`. What this scenario
  * DOES exercise for real is (a) that an empty committee cannot manufacture a spurious commit (a
  * concrete, checkable claim independent of the fork-detection gap above -- see the vote-verification
  * argument in step 2), and (b) whether a real committee handed back after a period of emptiness lets
  * the SAME already-running `HotStuffCoordinator.Enabled` instances resume committing, which is
  * exactly the resync/self-healing question production hit.
  */
class DstEmptyCommitteeSourceScenarioSpecification extends FlatSpec {
  private val SeedCount = 100

  private def committeeOf(stakes: Seq[Long]): GeneratorSet =
    stakes.zipWithIndex.map { case (stake, i) =>
      val kp = TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte))
      GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, stake)
    }

  private def blockAt(tag: Int): BlockId = ByteStr(Array.fill[Byte](32)(tag.toByte))

  private val realCommittee = committeeOf(Seq(25L, 25L, 25L, 25L))

  "a 4-node cluster whose committee source goes empty at a period boundary (no CommitToGenerationTransaction landed), then returns a real committee again" should
    "stay safe throughout, and reproduce whether or not it self-resumes committing once given a real committee again" in {
      var firstSafetyFailure: Option[(Long, String)] = None
      var falseCommitDuringEmptyWindow               = false
      var resumedCount                               = 0

      (0 until SeedCount).foreach { seed =>
        val harness = new DstHarness(seed, nodeCount = 4, FaultProfile(minDelayMillis = 1, maxDelayMillis = 3))

        // Step 1: healthy round under a non-empty committee, to confirm the cluster is otherwise
        // functioning before the fault is injected.
        harness.leaderTurn(node = 0, view = 0, blockId = blockAt(1), blockHeight = 100)
        harness.run()
        val committedBeforeFault = harness.commits.count(_.height == 100)

        // Step 2: period boundary -- no CommitToGenerationTransaction landed, so the committee source
        // now legitimately returns empty. A new round is attempted anyway (mirroring production: the
        // pacemaker/leader-timeout path keeps trying regardless of committee availability).
        harness.setCommittee(Seq.empty)
        harness.leaderTurn(node = 0, view = 1, blockId = blockAt(2), blockHeight = 101)
        harness.run()
        (1 to 10).foreach { _ =>
          harness.tickTimeoutAll()
          harness.run(maxEvents = 50)
        }
        harness.run()
        val commitsDuringEmptyWindow = harness.commits.count(_.height == 101)
        if (commitsDuringEmptyWindow > 0) falseCommitDuringEmptyWindow = true

        // Step 3: the automation eventually runs -- a real committee becomes available again. Drive a
        // fresh round exactly the way a live, still-running node would (a new leaderTurn plus
        // round-timer ticks to cover the case where the stall left the pacemaker/coordinator in a state
        // that needs a timeout to notice the committee is healthy again, not just a fresh proposal).
        harness.setCommittee(realCommittee)
        harness.leaderTurn(node = 0, view = 2, blockId = blockAt(3), blockHeight = 102)
        harness.run()
        (1 to 10).foreach { _ =>
          harness.tickTimeoutAll()
          harness.run(maxEvents = 50)
        }
        harness.run()

        val nodesCommittedAfterRecovery = harness.commits.filter(_.height == 102).map(_.node).toSet
        if (nodesCommittedAfterRecovery == Set(0, 1, 2, 3)) resumedCount += 1

        withClue(s"seed=$seed (committedBeforeFault=$committedBeforeFault): ") {
          SafetyInvariants.checkAll(harness.commits.toSeq, harness.votes.toSeq) match {
            case Left(reason) if firstSafetyFailure.isEmpty => firstSafetyFailure = Some((seed, reason))
            case _                                          => ()
          }
        }
      }

      // (a) Safety must hold throughout, including the empty-committee window, for every seed.
      firstSafetyFailure match {
        case None                 => succeed
        case Some((seed, reason)) =>
          fail(s"DST found a safety violation at seed=$seed with an empty-committee-source fault: $reason")
      }

      // A stronger, concrete safety claim specific to this scenario: an empty committee must never
      // itself produce a commit (vacuous-quorum hazard) -- see the vote-verification argument above.
      falseCommitDuringEmptyWindow should be(false)

      // (b) THE KEY FINDING. Per the task brief: do not weaken this assertion to force a pass. Report
      // exactly what the sweep found -- including if the honest result is "it depends".
      //
      // MEASURED RESULT, UPDATED 2026-09-02: resumedCount is now 100 / 100 seeds -- self-resumption is
      // RELIABLE, not flaky. It was 49/100 when this scenario was written, and the audit F-9 fix
      // (`voted`-set pruning in `HotStuffCoordinator.prunePool`, commit `f8c2650c52`) is the sole
      // cause of the change -- attributed by re-running this exact scenario at the pre-batch tip
      // (49/100), on the full batch (100/100), and on the batch with only F-9's coordinator change
      // reverted (49/100 again). See the class docstring for the full attribution table and the
      // stale-`voted`-entry mechanism, which is what CLOSES audit F-10 ("nobody has characterized why
      // it is 49%"): the coin-flip was the unbounded `voted` set retaining dead vote-guard entries
      // across the starvation window, not inherent timing nondeterminism.
      //
      // The band is kept as a GUARD (>= 90), not widened to accept anything: a regression back toward
      // the old coin-flip -- e.g. `voted` pruning being removed, narrowed, or made unreachable from the
      // round-timer path -- must fail this test loudly rather than being silently absorbed. If this
      // assertion starts failing, do NOT just widen the band -- re-read the new resumedCount, decide
      // whether it reflects a real behaviour change, and update this docstring (and the report) to say
      // so honestly.
      withClue(
        s"self-resume count = $resumedCount / $SeedCount seeds (expected >= 90; measured 100/100 on " +
          "2026-09-02 after the audit F-9 `voted`-pruning fix, up from 49/100 before it). A count back " +
          "near 50 would mean the F-9 pruning has regressed (removed, narrowed, or no longer reached " +
          "from the round-timer/`onTimeout` path) and the empty-committee recovery coin-flip is back; a " +
          "count near 0 would mean self-resumption is now reliably ABSENT. Either is a real, reportable " +
          "change -- update this file's docstring and the report, do not just widen the band silently."
      ) {
        resumedCount should be >= 90
      }
    }
}
