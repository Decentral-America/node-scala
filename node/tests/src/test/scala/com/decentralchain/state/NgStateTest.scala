package com.decentralchain.state

import com.decentralchain.common.state.ByteStr
import com.decentralchain.history.*
import com.decentralchain.test.*
import com.decentralchain.transaction.{GenesisTransaction, TxHelpers}
import com.decentralchain.transaction.transfer.*

class NgStateTest extends PropSpec {

  def preconditionsAndPayments(amt: Int): (GenesisTransaction, Seq[TransferTransaction]) = {
    val master    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)

    val genesis  = TxHelpers.genesis(master.toAddress)
    val payments = (1 to amt).map(idx => TxHelpers.transfer(master, recipient.toAddress, idx))

    (genesis, payments)
  }

  property("can forge correctly signed blocks") {
    val (genesis, payments)  = preconditionsAndPayments(10)
    val (block, microBlocks) = chainBaseAndMicro(randomSig, genesis, payments.map(t => Seq(t)))

    var ng = NgState(
      block,
      StateSnapshot.empty,
      0L,
      0L,
      ByteStr.empty,
      Set.empty,
      None,
      block.header.generationSignature,
      Map.empty,
      FinalizationState.notActivated(block)
    )
    microBlocks.foreach(m => ng = ng.append(m, StateSnapshot.empty, 0L, 0L, 0L, ByteStr.empty, None, Seq.empty))

    ng.liquidBlockOf(microBlocks.last.totalResBlockSig)
    microBlocks.foreach { m =>
      val forged = ng.liquidBlockOf(m.totalResBlockSig).get.block
      forged.signatureValid() shouldBe true
    }
    Seq(microBlocks(4)).map(x => ng.liquidBlockOf(x.totalResBlockSig))
  }

  property("can resolve best liquid block") {
    val (genesis, payments)  = preconditionsAndPayments(5)
    val (block, microBlocks) = chainBaseAndMicro(randomSig, genesis, payments.map(t => Seq(t)))

    var ng = NgState(
      block,
      StateSnapshot.empty,
      0L,
      0L,
      ByteStr.empty,
      Set.empty,
      None,
      block.header.generationSignature,
      Map.empty,
      FinalizationState.notActivated(block)
    )
    microBlocks.foreach(m => ng = ng.append(m, StateSnapshot.empty, 0L, 0L, 0L, ByteStr.empty, None, Seq.empty))

    ng.bestLiquidBlock.id() shouldBe microBlocks.last.totalResBlockSig

    new NgState(
      block,
      StateSnapshot.empty,
      0L,
      0L,
      ByteStr.empty,
      Set.empty,
      Some(0),
      block.header.generationSignature,
      Map.empty,
      FinalizationState.notActivated(block)
    ).bestLiquidBlock
      .id() shouldBe block
      .id()
  }

  property("can resolve best last block") {
    val (genesis, payments)  = preconditionsAndPayments(5)
    val (block, microBlocks) = chainBaseAndMicro(randomSig, genesis, payments.map(t => Seq(t)))

    var ng = NgState(
      block,
      StateSnapshot.empty,
      0L,
      0L,
      ByteStr.empty,
      Set.empty,
      None,
      block.header.generationSignature,
      Map.empty,
      FinalizationState.notActivated(block)
    )

    microBlocks.foldLeft(1000) { case (thisTime, m) =>
      ng = ng.append(m, StateSnapshot.empty, 0L, 0L, thisTime, ByteStr.empty, None, Seq.empty)
      thisTime + 50
    }

    ng.bestLastBlockInfo(0).blockId shouldBe block.id()
    ng.bestLastBlockInfo(1001).blockId shouldBe microBlocks.head.totalResBlockSig
    ng.bestLastBlockInfo(1051).blockId shouldBe microBlocks.tail.head.totalResBlockSig
    ng.bestLastBlockInfo(2000).blockId shouldBe microBlocks.last.totalResBlockSig

    new NgState(
      block,
      StateSnapshot.empty,
      0L,
      0L,
      ByteStr.empty,
      Set.empty,
      Some(0),
      block.header.generationSignature,
      Map.empty,
      FinalizationState.notActivated(block)
    ).bestLiquidBlock
      .id() shouldBe block
      .id()
  }

  property("calculates carry fee correctly") {
    val (genesis, payments)  = preconditionsAndPayments(5)
    val (block, microBlocks) = chainBaseAndMicro(randomSig, genesis, payments.map(t => Seq(t)))

    var ng = NgState(
      block,
      StateSnapshot.empty,
      0L,
      0L,
      ByteStr.empty,
      Set.empty,
      None,
      block.header.generationSignature,
      Map.empty,
      FinalizationState.notActivated(block)
    )
    microBlocks.foreach(m => ng = ng.append(m, StateSnapshot.empty, 1L, 0L, 0L, ByteStr.empty, None, Seq.empty))

    ng.liquidBlockOf(block.id()).map(_.data.carryFee) shouldBe Some(0L)
    microBlocks.zipWithIndex.foreach { case (m, i) =>
      val u = ng.liquidBlockOf(m.totalResBlockSig).map(_.data.carryFee)
      u shouldBe Some(i + 1)
    }
    ng.carryFee shouldBe microBlocks.size
  }
}
