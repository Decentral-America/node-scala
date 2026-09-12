package com.decentralchain.mining

import com.decentralchain.account.SeedKeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.settings.{DCCSettings, WalletSettings}
import com.decentralchain.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import com.decentralchain.state.{BlockEndorser, Height}
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.transaction.{CommitToGenerationTransaction, TransactionType, TxHelpers}
import com.decentralchain.utx.UtxPoolImpl
import com.decentralchain.wallet.Wallet
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Pure-function tests for `Miner.shouldSelfCommit` — the in-process replacement decision for the
  * external auto-commit-generators/commit-generators-hotstuff GH Actions crons. See
  * docs/superpowers/plans/2026-09-12-inprocess-self-commit-generation.md.
  */
class MinerSelfCommitSpecification extends AnyFreeSpec with Matchers with WithDomain {

  "shouldSelfCommit" - {
    "feature disabled => never self-commit, regardless of committee membership" in {
      Miner.shouldSelfCommit(enabled = false, alreadyCommitted = false) shouldBe false
      Miner.shouldSelfCommit(enabled = false, alreadyCommitted = true) shouldBe false
    }

    "feature enabled, already committed => do not re-submit" in {
      Miner.shouldSelfCommit(enabled = true, alreadyCommitted = true) shouldBe false
    }

    "feature enabled, not yet committed => self-commit" in {
      Miner.shouldSelfCommit(enabled = true, alreadyCommitted = false) shouldBe true
    }
  }

  private val generationPeriodLength = 8
  private val minerAcc: SeedKeyPair  = TxHelpers.signer(101)

  private val settingsWithSelfCommit: Boolean => DCCSettings = enabled =>
    DeterministicFinality
      .configure(_.copy(generationPeriodLength = generationPeriodLength))
      .setFeaturesHeight(BlockchainFeatures.DeterministicFinality -> 1)
      .copy(minerSettings = DeterministicFinality.minerSettings.copy(quorum = 0, selfCommitToGeneration = enabled))

  private def withMiner(dccSettings: DCCSettings)(f: (MinerImpl, Domain, UtxPoolImpl) => Unit): Unit =
    withDomain(dccSettings, AddrWithBalance.enoughBalances(minerAcc)) { d =>
      val time = TestTime()
      val utx  = new UtxPoolImpl(
        time,
        d.blockchainUpdater,
        dccSettings.utxSettings,
        dccSettings.maxTxErrorLogSize,
        isMiningEnabled = dccSettings.minerSettings.enable
      )
      val appenderScheduler = Scheduler.singleThread("appender-test")

      val miner = new MinerImpl(
        new DefaultChannelGroup(GlobalEventExecutor.INSTANCE),
        d.blockchainUpdater,
        dccSettings,
        time,
        utx,
        BlockEndorser.Disabled,
        d.endorsementStorage,
        Wallet(WalletSettings(None, Some("123"), Some(ByteStr(minerAcc.seed)))),
        d.posSelector,
        Scheduler.singleThread("miner-test"),
        appenderScheduler,
        Observable.empty
      )

      try f(miner, d, utx)
      finally {
        appenderScheduler.shutdown()
        utx.close()
      }
    }

  "maybeSelfCommit" - {
    // The "should I even attempt" decision is already fully covered, synchronously and without any
    // async/UTX-pool involvement, by the pure `Miner.shouldSelfCommit` tests above (in particular
    // "feature disabled => never self-commit, regardless of committee membership"). That predicate
    // is exactly what gates whether `maybeSelfCommit` dispatches anything at all, so there is no
    // additional disabled-path behavior for an integration-level test to exercise here.
    //
    // This test instead exists as a positive control on the full async/UTX-pool integration: it
    // proves that when the feature IS enabled the dispatched Task reliably lands a real
    // CommitToGenerationTransaction in the pool within a bounded window (via poll, not a fixed
    // sleep) -- so a future regression that silently no-ops `maybeSelfCommit` for an unrelated
    // reason (a broken gate, wrong period, a build failure inside the Task, ...) would fail this
    // test rather than passing vacuously the way a disabled-case sleep-then-assert-absence test
    // would.
    "submits a CommitToGenerationTransaction when enabled and not yet committed" in {
      withMiner(settingsWithSelfCommit(true)) { (miner, d, utx) =>
        val period = d.blockchain.generationPeriodOf(Height(d.blockchain.height)).get
        d.blockchain.committedGenerators(period.next).exists(_._1 == minerAcc.toAddress) shouldBe false

        miner.maybeSelfCommit(minerAcc, d.blockchain, period)

        val deadline = System.currentTimeMillis() + 5000
        while (utx.all.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)

        val committed = utx.all.collect { case tx: CommitToGenerationTransaction => tx }
        committed should have size 1
        committed.head.sender.toAddress shouldBe minerAcc.toAddress
      }
    }

    // Regression coverage for the retry-reentry finding: `forgeBlock` is not called exactly once per
    // height -- generateBlockTask recurses back into it on TemporaryFailure/Ignored/no-quorum paths,
    // re-entering at the SAME period boundary and re-running `maybeSelfCommit` each time. Since
    // CommitToGenerationTransaction's id is a FastHashId over bodyBytes (which includes a freshly
    // minted `timestamp` per attempt), naive re-dispatch would produce distinct tx ids that each pass
    // the UTX pool's containsKey-by-id dedup -- so this must be caught by an explicit per-(account,
    // period) in-process guard, not by putIfNew.
    "does not re-dispatch a second attempt for the same period on repeated forgeBlock-style re-entry" in {
      withMiner(settingsWithSelfCommit(true)) { (miner, d, utx) =>
        val period = d.blockchain.generationPeriodOf(Height(d.blockchain.height)).get

        miner.maybeSelfCommit(minerAcc, d.blockchain, period)

        val deadline = System.currentTimeMillis() + 5000
        while (utx.all.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)
        utx.all.collect { case tx: CommitToGenerationTransaction => tx } should have size 1

        // Simulate re-entry at the same period boundary (e.g. a TemporaryFailure/Ignored/no-quorum
        // retry looping back into forgeBlock before the first attempt has been mined into a block).
        miner.maybeSelfCommit(minerAcc, d.blockchain, period)
        miner.maybeSelfCommit(minerAcc, d.blockchain, period)

        // Give any (wrongly) re-dispatched task a real chance to run before asserting it didn't.
        Thread.sleep(300)

        val committed = utx.all.collect { case tx: CommitToGenerationTransaction => tx }
        committed should have size 1
        committed.head.sender.toAddress shouldBe minerAcc.toAddress
      }
    }

    // Fee must be the standard transaction fee for this type, NOT `DepositInDcclets` (100 DCC).
    // `DepositInDcclets` is the generation DEPOSIT, derived implicitly from committee membership by
    // BalanceDiffValidation/BlockDiffer -- it is never paid as a fee. Pinning the exact same
    // expression the REST construction path (`CommitToGenerationRequest.toTxFrom`) defaults to, so
    // the two construction paths for one transaction type cannot silently drift apart economically.
    "uses the standard transaction fee, not the generation deposit" in {
      withMiner(settingsWithSelfCommit(true)) { (miner, d, utx) =>
        val period = d.blockchain.generationPeriodOf(Height(d.blockchain.height)).get
        miner.maybeSelfCommit(minerAcc, d.blockchain, period)

        val deadline = System.currentTimeMillis() + 5000
        while (utx.all.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)

        val committed = utx.all.collect { case tx: CommitToGenerationTransaction => tx }
        committed should have size 1
        committed.head.fee.value shouldBe FeeConstants(TransactionType.CommitToGeneration) * FeeUnit
        committed.head.fee.value should not be CommitToGenerationTransaction.DepositInDcclets
      }
    }
  }

  "forgeBlock self-commit call site" - {
    // THE regression test for the critical timing finding. `maybeSelfCommit` used to be called only
    // from inside `.filter(_.end == newBlockHeight)` -- i.e. at the single height that is the LAST of a
    // generation period. That gave the transaction one liquid period's microblock window to land (and
    // not even the block being forged, since `packTransactionsForKeyBlock` has already run by that
    // point), with no retry ever. A CommitToGenerationTransaction is valid for the WHOLE of `period`
    // (CommitToGenerationTransactionDiff checks `tx.generationPeriodStart ==
    // currentGenerationPeriod.next.start`), so the attempt belongs on EVERY key-block forge within the
    // period -- matching the every-5-to-10-minutes cadence of the GH Actions cron this replaces, which
    // was built that way precisely because single-shot attempts were observed failing live 4/4 times.
    //
    // Asserted by forging at a height that is deliberately NOT the period boundary and requiring a
    // commit to appear. `forgeBlock`'s own Either-chain result is intentionally ignored: the new call
    // site sits OUTSIDE `metrics.blockBuildTimeStats`/`stopReasons`/`retryReasons` as a pure side
    // effect, so self-commit must fire even when the forge attempt itself fails (no quorum, PoS delay,
    // ...). That independence is itself part of what is being asserted here.
    "dispatches a self-commit at a NON-boundary height within the period" in {
      withMiner(settingsWithSelfCommit(true)) { (miner, d, utx) =>
        // Walk forward until the block we are about to forge is strictly inside the period, never its
        // last height -- the exact case the old boundary-only call site could not handle.
        val period = d.blockchain.generationPeriodOf(Height(d.blockchain.height + 1)).get
        while (
          d.blockchain.generationPeriodOf(Height(d.blockchain.height + 1)).contains(period) &&
          Height(d.blockchain.height + 1) == period.end
        ) d.appendBlock()

        val forgeHeight = Height(d.blockchain.height + 1)
        val forgePeriod = d.blockchain.generationPeriodOf(forgeHeight).get
        withClue(s"precondition: forge height $forgeHeight must not be the period end of $forgePeriod") {
          forgeHeight should not be forgePeriod.end
        }
        d.blockchain.committedGenerators(forgePeriod.next).exists(_._1 == minerAcc.toAddress) shouldBe false

        miner.forgeBlock(minerAcc)

        val deadline = System.currentTimeMillis() + 5000
        while (utx.all.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)

        val committed = utx.all.collect { case tx: CommitToGenerationTransaction => tx }
        withClue("self-commit must be attempted at every forge within the period, not only at period.end: ") {
          committed should have size 1
        }
        committed.head.sender.toAddress shouldBe minerAcc.toAddress
        // It must target the NEXT period -- i.e. the value `CommitToGenerationTransactionDiff` will
        // compare against `currentGenerationPeriod.next.start` for the rest of this period.
        committed.head.generationPeriodStart shouldBe forgePeriod.next.start
      }
    }

    // The counterpart risk of "call it at every forge": now that the same period is visited many
    // times, the per-(account, period.next) claim guard is what stops the UTX pool filling with
    // near-duplicate commits. It cannot be left to `utx.putIfNew`, whose dedup is by transaction id --
    // and CommitToGenerationTransaction's id is a FastHashId over bodyBytes, which includes a freshly
    // minted `timestamp` per attempt, so every re-dispatch would mint a DISTINCT id and be accepted.
    // Exercised across many forges at several distinct heights of one period, which is the real
    // production shape (previously only same-height re-entry was covered).
    "dispatches exactly one self-commit across many forges at many heights in the same period" in {
      withMiner(settingsWithSelfCommit(true)) { (miner, d, utx) =>
        val period = d.blockchain.generationPeriodOf(Height(d.blockchain.height + 1)).get

        var forges = 0
        // Stay strictly within `period`: crossing into `period.next` would legitimately entitle the
        // account to a fresh attempt for a different target, which is not what this test is about.
        while (d.blockchain.generationPeriodOf(Height(d.blockchain.height + 1)).contains(period)) {
          miner.forgeBlock(minerAcc)
          miner.forgeBlock(minerAcc) // same height twice: the generateBlockTask retry-re-entry shape
          forges += 2
          if (forges == 2) {
            // Also pins the TIMING property here, not just the dedup one: after the very FIRST forge
            // of the period -- many heights before `period.end` -- the commit must already be in
            // flight. Under the old boundary-only call site the pool is still empty at this point.
            val d0 = System.currentTimeMillis() + 5000
            while (utx.all.isEmpty && System.currentTimeMillis() < d0) Thread.sleep(50)
            withClue(s"commit must be in flight after the first forge of $period, not deferred to period.end: ") {
              utx.all.collect { case tx: CommitToGenerationTransaction => tx } should have size 1
            }
          }
          d.appendBlock()
        }
        withClue("test must actually exercise multiple forges: ") { forges should be > 2 }

        val deadline = System.currentTimeMillis() + 5000
        while (utx.all.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)
        // Give any wrongly re-dispatched task a real chance to run before asserting it did not.
        Thread.sleep(300)

        val committed = utx.all.collect { case tx: CommitToGenerationTransaction => tx }
        withClue(s"after $forges forges in period $period: ") { committed should have size 1 }
        committed.head.generationPeriodStart shouldBe period.next.start
      }
    }

    // The on-chain gate, not the in-process claim guard, is what must suppress the attempt for an
    // account that is ALREADY a committed generator for the upcoming period -- proven here with a
    // fresh miner instance (empty claim map), so only `committedGenerators(period.next)` can be doing
    // the work. This is the gate that keeps "call at every forge" cheap in production, where a node
    // restarts mid-period with its commit already on chain.
    "does not re-commit when the account is already an on-chain committed generator for the next period" in {
      withMiner(settingsWithSelfCommit(true)) { (miner, d, utx) =>
        val period = d.blockchain.generationPeriodOf(Height(d.blockchain.height + 1)).get
        d.appendBlock(
          TxHelpers.commitToGeneration(period.next.start, minerAcc)
        )
        d.blockchain.committedGenerators(period.next).exists(_._1 == minerAcc.toAddress) shouldBe true

        miner.forgeBlock(minerAcc)
        Thread.sleep(500)

        utx.all.collect { case tx: CommitToGenerationTransaction => tx } shouldBe empty
      }
    }
  }
}
