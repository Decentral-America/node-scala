package com.decentralchain.state.diffs.smart.scenarios

import com.decentralchain.account.PublicKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto
import com.decentralchain.db.WithState
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.lang.directives.values.{Expression, V1}
import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.lang.utils.*
import com.decentralchain.lang.v1.compiler.ExpressionCompiler
import com.decentralchain.lang.v1.compiler.Terms.*
import com.decentralchain.lang.v1.parser.Parser
import com.decentralchain.state.diffs.smart.*
import com.decentralchain.test.*
import com.decentralchain.transaction.*
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.transfer.*

class MultiSig2of3Test extends PropSpec with WithState {

  def multisigTypedExpr(pk0: PublicKey, pk1: PublicKey, pk2: PublicKey): EXPR = {
    val script =
      s"""
         |
         |let A = base58'$pk0'
         |let B = base58'$pk1'
         |let C = base58'$pk2'
         |
         |let proofs = tx.proofs
         |let AC = if(sigVerify(tx.bodyBytes,proofs[0],A)) then 1 else 0
         |let BC = if(sigVerify(tx.bodyBytes,proofs[1],B)) then 1 else 0
         |let CC = if(sigVerify(tx.bodyBytes,proofs[2],C)) then 1 else 0
         |
         | AC + BC+ CC >= 2
         |
      """.stripMargin
    val untyped = Parser.parseExpr(script).get.value
    ExpressionCompiler(compilerContext(V1, Expression, isAssetScript = false), V1, untyped).explicitGet()._1
  }

  val preconditionsAndTransfer: (GenesisTransaction, SetScriptTransaction, TransferTransaction, Seq[ByteStr]) = {
    val master    = TxHelpers.signer(1)
    val s0        = TxHelpers.signer(2)
    val s1        = TxHelpers.signer(3)
    val s2        = TxHelpers.signer(4)
    val recipient = TxHelpers.signer(5)

    val genesis          = TxHelpers.genesis(master.toAddress)
    val setScript        = TxHelpers.setScript(master, ExprScript(multisigTypedExpr(s0.publicKey, s1.publicKey, s2.publicKey)).explicitGet())
    val transferUnsigned = TxHelpers.transferUnsigned(master, recipient.toAddress)

    val sig0 = crypto.sign(s0.privateKey, transferUnsigned.bodyBytes())
    val sig1 = crypto.sign(s1.privateKey, transferUnsigned.bodyBytes())
    val sig2 = crypto.sign(s2.privateKey, transferUnsigned.bodyBytes())

    (genesis, setScript, transferUnsigned, Seq(sig0, sig1, sig2))
  }

  property("2 of 3 multisig") {

    val (genesis, script, transfer, sigs) = preconditionsAndTransfer
    val validProofs                       = Seq(
      transfer.copy(proofs = Proofs.create(Seq(sigs(0), sigs(1))).explicitGet()),
      transfer.copy(proofs = Proofs.create(Seq(ByteStr.empty, sigs(1), sigs(2))).explicitGet())
    )

    val invalidProofs = Seq(
      transfer.copy(proofs = Proofs.create(Seq(sigs(0))).explicitGet()),
      transfer.copy(proofs = Proofs.create(Seq(sigs(1))).explicitGet()),
      transfer.copy(proofs = Proofs.create(Seq(sigs(1), sigs(0))).explicitGet())
    )

    validProofs.foreach { tx =>
      assertDiffAndState(Seq(TestBlock.create(Seq(genesis, script))), TestBlock.create(Seq(tx)), smartEnabledFS) { case _ => () }
    }
    invalidProofs.foreach { tx =>
      assertLeft(Seq(TestBlock.create(Seq(genesis, script))), TestBlock.create(Seq(tx)), smartEnabledFS)("TransactionNotAllowedByScript")
    }
  }
}
