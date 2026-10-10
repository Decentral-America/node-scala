package com.decentralchain.finalization

import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{Block, FinalizationVoting}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.GeneratingBalanceProvider.MinimalEffectiveBalanceForGenerator2
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.mining.{ForgeAttemptResult, Miner, MinerImpl}
import com.decentralchain.state.*
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.TestValues
import com.decentralchain.account.Address
import com.decentralchain.transaction.{CommitToGenerationTransaction, TxHelpers}
import com.decentralchain.wallet.Wallet
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import org.scalatest.time.SpanSugar.convertLongToGrainOfTime

/** A key block must reference the liquid tip as it is AFTER the endorsement grace, not before it.
  *
  * Live testnet 2026-10-10: the miner chose its reference and then blocked up to 1200ms in
  * tryCollectSelfWithGrace, so every microblock the liquid-block owner appended during that window
  * was orphaned, and transactions depending on the orphaned ones were evicted from the UTX pool.
  */
class KeyBlockReferenceAfterGraceSuite extends BaseFinalizationSpec {
  private val thisNodeAcc  = Wallet.generateNewAccount(Domain.DefaultWalletSeed, nonce = 0)
  private val otherNodeAcc = TxHelpers.defaultSigner

  private val baseSettings    = DomainPresets.DeterministicFinality.addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
  private val defaultSettings = baseSettings
    .copy(minerSettings = baseSettings.minerSettings.copy(quorum = 0, microBlockInterval = 100.millis))
    .configure(_.copy(generationPeriodLength = 2))

  /** Enabled endorser that never collects anything, and runs `onGrace` the first time the miner polls it. */
  private class MicroBlockDuringGrace(onGrace: () => Unit) extends BlockEndorser {
    @volatile private var fired                                                                   = false
    @volatile var collectedFor: Seq[BlockId]                                                      = Seq.empty
    override def vote(generatorSet: GeneratorSet): Unit                                           = {}
    override def voteSelf(generatorSet: GeneratorSet): Unit                                       = {}
    override def rebroadcast(): Unit                                                              = {}
    override def enabled: Boolean                                                                 = true
    override def tryCollectSelf(endorsedId: BlockId, forger: Address): Option[FinalizationVoting] = synchronized {
      collectedFor :+= endorsedId
      if (!fired) {
        fired = true
        onGrace()
      }
      None
    }
  }

  "Key block references a microblock appended during the endorsement grace" in withManager { manager =>
    val channels = manager(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
    withDomain(
      defaultSettings,
      AddrWithBalance.enoughBalances(otherNodeAcc) ++ Seq(
        AddrWithBalance(
          thisNodeAcc.toAddress,
          MinimalEffectiveBalanceForGenerator2 + TestValues.commitToGenerationFee + CommitToGenerationTransaction.DepositInDcclets
        )
      ),
      miner = Miner.StrictDisabledMiner
    ) { d =>
      d.wallet.generateNewAccounts(1)

      val commits = Seq(otherNodeAcc, thisNodeAcc).map(x => TxHelpers.commitToGeneration(Height(3), sender = x))
      d.appender.appendBlock(d.createBlock(version = Block.ProtoBlockVersion, txs = commits, generator = otherNodeAcc, strictTime = true))
      val staleTip = d.lastBlockId

      var microTip = ByteStr.empty
      val endorser = new MicroBlockDuringGrace(() => microTip = d.appendMicroBlock(TxHelpers.transfer(otherNodeAcc, TxHelpers.signer(7).toAddress)))

      val minerImpl = new MinerImpl(
        channels,
        d.blockchain,
        d.settings,
        d.testTime,
        d.utxPool,
        endorser,
        EndorsementStorage.Disabled,
        d.wallet,
        d.posSelector,
        TestScheduler(),
        TestScheduler(),
        Observable.empty
      )

      d.testTime.setTimeIfGreater(d.nextBlockTime(thisNodeAcc))
      val block = minerImpl.forgeBlock(thisNodeAcc) match {
        case ForgeAttemptResult.Success(block, _) => block
        case other                                => fail(s"forge failed: $other")
      }

      microTip should not be ByteStr.empty
      d.lastBlockId shouldBe microTip
      block.header.reference shouldBe microTip
      block.header.reference should not be staleTip
      // The grace polled the pre-grace tip; the moved tip then got exactly one immediate collect.
      endorser.collectedFor.head shouldBe staleTip
      endorser.collectedFor.last shouldBe microTip
      d.appender.appendBlock(block)
      d.lastBlockId shouldBe block.id()
    }
  }
}
