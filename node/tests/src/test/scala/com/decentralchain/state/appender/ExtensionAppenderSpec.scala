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
      val extensionAppender = ExtensionAppender(d.blockchain, utx, d.posSelector, time, InvalidBlockStorage.NoOp, PeerDatabase.NoOp, Int.MaxValue, global)(null, _)

      val tx     = TxHelpers.transfer()
      val block1 = d.createBlock(Seq(tx), strictTime = true, version = Block.PlainBlockVersion)
      utx.putIfNew(tx).resultE.explicitGet()
      d.appendBlock(tx)
      utx.all shouldBe Seq(tx)

      time.setTime(block1.header.timestamp)
      extensionAppender(ExtensionBlocks(d.blockchain.score + block1.blockScore(), Seq(block1), Map.empty, new EmbeddedChannel())).runSyncUnsafe().explicitGet()
      d.blockchain.height shouldBe 2
      utx.all shouldBe Nil
      utx.close()
    }

  it should "reject a higher-score fork that branches below the finality floor" in
    withDomain(balances = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner)) { d =>
      val utx = new UtxPoolImpl(SystemTime, d.blockchain, d.settings.utxSettings, d.settings.maxTxErrorLogSize, d.settings.minerSettings.enable)

      // Common base up to height 3, then a competing fork X (heights 4..6) branching at height 3.
      d.appendBlock()
      d.appendBlock()
      val branchRef  = d.lastBlockId
      val forkBlocks = List(d.appendBlock(), d.appendBlock(), d.appendBlock()) // domain now on fork X at height 6

      // Node's own canonical chain Y (heights 4..5), branching at the same point.
      d.rollbackTo(branchRef)
      d.appendBlock()
      d.appendBlock() // domain on Y at height 5

      // maxSyncRollbackLength = 1 => finality floor = max(finalized, 5 - 1) = 4; fork X branches at height 3 (< 4).
      val appender = ExtensionAppender(
        d.blockchain,
        utx,
        d.posSelector,
        TestTime(forkBlocks.last.header.timestamp),
        InvalidBlockStorage.NoOp,
        PeerDatabase.NoOp,
        1,
        global
      )(new EmbeddedChannel(), _)

      val declaredHigherScore = d.blockchain.score + BigInt(10).pow(40)
      val result              = appender(ExtensionBlocks(declaredHigherScore, forkBlocks, Map.empty, new EmbeddedChannel())).runSyncUnsafe()

      result match {
        case Left(e)  => e.toString should include("finality floor")
        case Right(_) => fail("Fork branching below the finality floor must be rejected")
      }
      d.blockchain.height shouldBe 5 // canonical chain unchanged
      utx.close()
    }

}
