package com.decentralchain.mining

import com.decentralchain.account.SeedKeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.settings.{DCCSettings, WalletSettings}
import com.decentralchain.state.{BlockEndorser, Height}
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.transaction.{CommitToGenerationTransaction, TxHelpers}
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
      .copy(minerSettings =
        DeterministicFinality.minerSettings.copy(quorum = 0, selfCommitToGeneration = enabled)
      )

  private def withMiner(dccSettings: DCCSettings)(f: (MinerImpl, Domain, UtxPoolImpl) => Unit): Unit =
    withDomain(dccSettings, AddrWithBalance.enoughBalances(minerAcc)) { d =>
      val time              = TestTime()
      val utx               = new UtxPoolImpl(
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
  }
}
