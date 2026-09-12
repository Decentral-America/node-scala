package com.decentralchain.mining

import com.decentralchain.account.SeedKeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.settings.{WalletSettings, DCCSettings}
import com.decentralchain.state.BlockEndorser
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

  private def withMiner(dccSettings: DCCSettings)(f: (MinerImpl, com.decentralchain.history.Domain, UtxPoolImpl) => Unit): Unit =
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
    "does nothing when selfCommitToGeneration is disabled, even if not committed" in {
      withMiner(settingsWithSelfCommit(false)) { (miner, d, utx) =>
        val period = d.blockchain.generationPeriodOf(com.decentralchain.state.Height(d.blockchain.height)).get
        utx.all.exists(_.isInstanceOf[CommitToGenerationTransaction]) shouldBe false

        miner.maybeSelfCommit(minerAcc, d.blockchain, period)
        // fire-and-forget on appenderScheduler -- give it a moment to (not) run
        Thread.sleep(200)

        utx.all.exists(_.isInstanceOf[CommitToGenerationTransaction]) shouldBe false
      }
    }

    "submits a CommitToGenerationTransaction when enabled and not yet committed" in {
      withMiner(settingsWithSelfCommit(true)) { (miner, d, utx) =>
        val period = d.blockchain.generationPeriodOf(com.decentralchain.state.Height(d.blockchain.height)).get
        d.blockchain.committedGenerators(period.next).exists(_._1 == minerAcc.toAddress) shouldBe false

        miner.maybeSelfCommit(minerAcc, d.blockchain, period)

        val deadline = System.currentTimeMillis() + 5000
        while (utx.all.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(50)

        val committed = utx.all.collect { case tx: CommitToGenerationTransaction => tx }
        committed should have size 1
        committed.head.sender.toAddress shouldBe minerAcc.toAddress
      }
    }
  }
}
