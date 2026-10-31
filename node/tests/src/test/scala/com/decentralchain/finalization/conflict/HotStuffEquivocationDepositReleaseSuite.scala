package com.decentralchain.finalization.conflict

import com.decentralchain.TestValues
import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffEquivocationProof, HotStuffQuorum}
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.finalization.BaseFinalizationSpec
import com.decentralchain.history.Domain
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.diffs.ENOUGH_AMT
import com.decentralchain.state.{BalanceSnapshot, GeneratorIndex, Height, Portfolio}
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.transaction.CommitToGenerationTransaction.DepositInDcclets
import com.decentralchain.transaction.TxHelpers
import io.decentralchain.protobuf.block.HotStuffPhase

/** ECO-49: a forfeited HotStuff-equivocator deposit must not come back.
  *
  * The generation deposit is a LOCK on the regular balance (no debit on commit); it is unlocked at the end of the committed
  * period, or earlier at the punishment height for a conflicting generator (RocksDBWriter.collectGenerationDepositChanges,
  * Blockchain.generationDeposit). Since `hotStuffEquivocationPenaltyHeight`, BlockDiffer.calculatePenalties also DEBITS the
  * deposit from a HotStuff equivocator's regular balance. This suite proves the two compose to exactly one loss of the
  * deposit: the lock is not still held after the debit (no double penalty), and nothing re-credits or re-releases the
  * deposit at the punishment height, at the natural end of the period, or on any later block -- including across a rollback
  * and re-append. Below the gate the historic behaviour (lock released, no debit) is unchanged, so existing chain history
  * replays identically.
  *
  * Chain (generation period length 2): 1 genesis; 2 commitments for period [3, 4]; 3 carries the equivocation proof (first
  * block of the period); 4 applies the penalty; 5 is the first block of the next period (the natural release height of the
  * period-[3, 4] deposit); 6 and 7 are later blocks.
  */
class HotStuffEquivocationDepositReleaseSuite extends BaseFinalizationSpec {
  private val miner     = TxHelpers.signer(0)
  private val offender  = TxHelpers.signer(2)
  private val offIdx    = GeneratorIndex(2)
  private val committee = Seq(miner, TxHelpers.signer(1), offender, TxHelpers.signer(3))

  private def settings(penaltyHeight: Int) =
    DomainPresets.DeterministicFinality
      .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
      .configure(_.copy(generationPeriodLength = 2, lightNodeBlockFieldsAbsenceInterval = 0, hotStuffEquivocationPenaltyHeight = penaltyHeight))

  private val periodIndex = 1

  private def vote(signer: KeyPair, blockIdByte: Byte): HotStuffVote = {
    val phase   = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
    val blockId = ByteStr(Array.fill(32)(blockIdByte))
    val sig     = BlsKeyPair(signer.privateKey).sign(HotStuffQuorum.voteMessage(5, phase, blockId, 10, periodIndex), HotStuffQuorum.VoteDst)
    HotStuffVote(5, phase, blockId, Height(10), offIdx.toInt, ByteStr(sig.arr), periodIndex)
  }

  private val proof = HotStuffEquivocationProof(vote(offender, 1), vote(offender, 2))

  private val afterCommit = ENOUGH_AMT - TestValues.commitToGenerationFee
  private val forfeited   = afterCommit - DepositInDcclets

  /** What the offender's account looks like after a block. */
  private case class Observed(
      height: Int,
      portfolio: Portfolio,
      generatingBalance: Long,
      balanceAtHeight: (Int, Long),
      snapshots: Seq[BalanceSnapshot],
      dccAmount: BigInt
  )

  private def observe(d: Domain): Observed = Observed(
    d.blockchain.height,
    d.blockchain.dccPortfolio(offender.toAddress),
    d.blockchain.generatingBalance(offender.toAddress),
    d.blockchain.balanceAtHeight(offender.toAddress, d.blockchain.height).value,
    d.blockchain.balanceSnapshots(offender.toAddress, from = 2, to = None),
    d.blockchain.dccAmount(d.blockchain.height)
  )

  private def emptyBlock(d: Domain): Block = d.createBlock(Block.ProtoBlockVersion, Nil, generator = miner, strictTime = true)

  /** Appends blocks 2..7 and observes after each; then rolls back to 4 and re-appends 5..7, observing again. */
  private def run(penaltyHeight: Int): (Map[Int, Observed], Map[Int, Observed]) = {
    var result = Option.empty[(Map[Int, Observed], Map[Int, Observed])]
    withDomain(settings(penaltyHeight), AddrWithBalance.enoughBalances(committee*)) { d =>
      val commits = committee.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
      d.appender.appendBlockWithoutFallback(d.createBlock(Block.ProtoBlockVersion, commits, generator = miner, strictTime = true)) should beRight
      val obs = Map.newBuilder[Int, Observed]
      obs += 2 -> observe(d)

      val block3 = d.createBlock(
        Block.ProtoBlockVersion,
        Nil,
        generator = miner,
        strictTime = true,
        finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
      )
      d.appender.appendBlockWithoutFallback(block3) should beRight
      obs += 3 -> observe(d)

      val block4 = emptyBlock(d)
      d.appender.appendBlockWithoutFallback(block4) should beRight
      obs += 4 -> observe(d)

      (5 to 7).foreach { h =>
        d.appender.appendBlockWithoutFallback(emptyBlock(d)) should beRight
        d.blockchain.height shouldBe h
        obs += h -> observe(d)
      }

      // Rollback below the natural release height and re-append: the release must not be re-applied.
      d.blockchainUpdater.removeAfter(block4.id()) should beRight
      val reObs = Map.newBuilder[Int, Observed]
      reObs += 4 -> observe(d)
      (5 to 7).foreach { h =>
        d.appender.appendBlockWithoutFallback(emptyBlock(d)) should beRight
        d.blockchain.height shouldBe h
        reObs += h -> observe(d)
      }
      result = Some((obs.result(), reObs.result()))
    }
    result.value
  }

  private lazy val forfeitRun   = run(penaltyHeight = 3) // proof at 3 >= gate: forfeit
  private lazy val noForfeitRun = run(penaltyHeight = 4) // proof at 3 < gate: historic behaviour

  "forfeited deposit (proof at the penalty height)" - {
    "is locked, not debited, until the penalty block" in {
      val (obs, _) = forfeitRun
      Seq(2, 3).foreach { h =>
        withClue(s"after block $h: ") {
          obs(h).portfolio shouldBe Portfolio(balance = afterCommit, generationDeposit = DepositInDcclets)
          obs(h).generatingBalance shouldBe afterCommit - DepositInDcclets
        }
      }
    }

    "is debited exactly once at the penalty block and its lock is released at the same time (no double penalty)" in {
      val (obs, _) = forfeitRun
      obs(4).portfolio shouldBe Portfolio(balance = forfeited)
      obs(4).balanceAtHeight shouldBe (4, forfeited)
      obs(4).generatingBalance shouldBe forfeited
    }

    "is never released back or re-credited: not at the natural end of the period (5) nor later (6, 7)" in {
      val (obs, _) = forfeitRun
      (4 to 7).foreach { h =>
        withClue(s"after block $h: ") {
          obs(h).portfolio shouldBe Portfolio(balance = forfeited)
          obs(h).balanceAtHeight shouldBe (4, forfeited) // last regular-balance change is the penalty itself
          // A minimum over a window of past heights: a still-held lock would push it to forfeited - deposit
          obs(h).generatingBalance shouldBe forfeited
        }
      }
    }

    "balance history shows the lock until the penalty and neither a lock nor a re-credit afterwards" in {
      val (obs, _)  = forfeitRun
      val snapshots = obs(7).snapshots
      snapshots.filter(_.height >= Height(4)) should not be empty
      snapshots.filter(_.height >= Height(4)).foreach { s =>
        withClue(s"snapshot at ${s.height}: ") {
          s.regularBalance shouldBe forfeited
          s.generationDeposit shouldBe 0L
        }
      }
      snapshots.filter(s => s.height >= Height(2) && s.height < Height(4)).foreach { s =>
        withClue(s"snapshot at ${s.height}: ") {
          s.regularBalance shouldBe afterCommit
          s.generationDeposit shouldBe DepositInDcclets
        }
      }
    }

    "the deposit leaves the recorded total supply (totalDccAmount) for good" in {
      val (forfeit, _)   = forfeitRun
      val (noForfeit, _) = noForfeitRun
      Seq(2, 3).foreach(h => forfeit(h).dccAmount shouldBe noForfeit(h).dccAmount)
      (4 to 7).foreach(h => withClue(s"height $h: ")(forfeit(h).dccAmount shouldBe noForfeit(h).dccAmount - DepositInDcclets))
    }

    "rollback to the penalty block and re-append gives identical results (the release is not re-applied)" in {
      val (obs, reObs) = forfeitRun
      (4 to 7).foreach(h => withClue(s"height $h: ")(reObs(h) shouldBe obs(h)))
    }
  }

  "below the penalty height (pre-activation history): lock released at the punishment height, no debit -- unchanged" in {
    val (obs, reObs) = noForfeitRun
    Seq(2, 3).foreach(h => obs(h).portfolio shouldBe Portfolio(balance = afterCommit, generationDeposit = DepositInDcclets))
    (4 to 7).foreach { h =>
      withClue(s"after block $h: ") {
        obs(h).portfolio shouldBe Portfolio(balance = afterCommit)
        // generatingBalance is a minimum over a window of past heights, which still includes the locked heights 2-3
        obs(h).generatingBalance shouldBe afterCommit - DepositInDcclets
        obs(h).snapshots.filter(_.height >= Height(4)).foreach { s =>
          s.regularBalance shouldBe afterCommit
          s.generationDeposit shouldBe 0L
        }
        reObs(h) shouldBe obs(h)
      }
    }
  }
}
