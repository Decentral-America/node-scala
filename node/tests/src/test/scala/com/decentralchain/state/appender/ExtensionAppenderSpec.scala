package com.decentralchain.state.appender

import com.decentralchain.block.Block
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.network.{ExtensionBlocks, InvalidBlockStorage, PeerDatabase}
import com.decentralchain.test.*
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.utils.SystemTime
import com.decentralchain.utx.UtxPoolImpl
import io.netty.channel.embedded.EmbeddedChannel
import monix.execution.Scheduler.Implicits.global

class ExtensionAppenderSpec extends FlatSpec with WithDomain {
  "Extension appender" should "drop duplicate transactions from UTX" in
    withDomain(balances = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner)) { d =>
      val utx  = new UtxPoolImpl(SystemTime, d.blockchain, d.settings.utxSettings, d.settings.maxTxErrorLogSize, d.settings.minerSettings.enable)
      val time = TestTime()
      val extensionAppender = ExtensionAppender(d.blockchain, utx, d.posSelector, time, InvalidBlockStorage.NoOp, PeerDatabase.NoOp, global)(null, _)

      val tx     = TxHelpers.transfer()
      val block1 = d.createBlock(Block.PlainBlockVersion, Seq(tx), strictTime = true)
      utx.putIfNew(tx).resultE.explicitGet()
      d.appendBlock(tx)
      utx.all shouldBe Seq(tx)

      time.setTime(block1.header.timestamp)
      extensionAppender(ExtensionBlocks(d.blockchain.score + block1.blockScore(), Seq(block1), Map.empty)).runSyncUnsafe().explicitGet()
      d.blockchain.height shouldBe 2
      utx.all shouldBe Nil
      utx.close()
    }

  // Regression for the fork-discarded-microblock UTX drop. A transaction that lives only in a
  // microblock must be returned to the mempool when a competing key-block arrives via SYNC
  // (extension) and references the base of the current liquid block, ignoring our microblocks.
  // appendKeyBlock (broadcast) already does this via setPrioritySnapshots(discardedDiffs); before
  // the fix, appendExtensionBlock threw those diffs away and ExtensionAppender's droppedBlocks
  // re-add is empty here (commonBlockHeight == initialHeight → "Resetting liquid block, no rollback
  // necessary"), so the tx was silently lost forever.
  it should "return fork-discarded microblock transactions to the UTX pool (sync/extension path)" in
    withDomain(DomainPresets.RideV6, balances = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner)) { d =>
      val utx  = new UtxPoolImpl(SystemTime, d.blockchain, d.settings.utxSettings, d.settings.maxTxErrorLogSize, d.settings.minerSettings.enable)
      val time = TestTime()
      val extensionAppender =
        ExtensionAppender(d.blockchain, utx, d.posSelector, time, InvalidBlockStorage.NoOp, PeerDatabase.NoOp, global)(new EmbeddedChannel(), _)

      // Base key block, then a microblock carrying `issue` into the current liquid block.
      val baseId = d.appendKeyBlock().id()
      val issue  = TxHelpers.issue(TxHelpers.defaultSigner)
      d.appendMicroBlock(issue)
      d.blockchain.transactionInfo(issue.id()) shouldBe defined
      utx.all shouldBe Nil

      // A competing key block that references the BASE (ignoring our microblock), delivered as a
      // sync extension. Applying it discards the microblock that held `issue`.
      val competing = d.createBlock(Block.NgBlockVersion, Nil, ref = Some(baseId), strictTime = true)
      time.setTime(competing.header.timestamp)
      extensionAppender(ExtensionBlocks(d.blockchain.score + competing.blockScore(), Seq(competing), Map.empty)).runSyncUnsafe().explicitGet()

      d.blockchain.transactionInfo(issue.id()) shouldBe None // discarded from the chain
      utx.all shouldBe Seq(issue)                            // ...and returned to the mempool (the fix)
      utx.close()
    }

}
