package com.decentralchain.state.diffs.ci
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.lang.contract.DApp.{CallableAnnotation, CallableFunction}
import com.decentralchain.lang.contract.DApp
import com.decentralchain.lang.directives.values.*
import com.decentralchain.lang.script.ContractScript.ContractScriptImpl
import com.decentralchain.lang.v1.FunctionHeader.{Native, User}
import com.decentralchain.lang.v1.compiler.Terms.{CONST_STRING, FUNC, FUNCTION_CALL, REF}
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.lang.v1.evaluator.FunctionIds.CREATE_LIST
import com.decentralchain.lang.v1.evaluator.ctx.impl.GlobalValNames
import io.decentralchain.protobuf.dapp.DAppMeta
import com.decentralchain.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import com.decentralchain.test.*
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.TransactionType
import com.decentralchain.transaction.TxHelpers.{invoke, issue, secondAddress, secondSigner, setScript}
import com.decentralchain.transaction.smart.InvokeScriptTransaction.Payment

class ScriptTransferTest extends PropSpec with WithDomain {
  import DomainPresets.*

  property("wrong address bytes length") {
    withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      val dApp = TestCompiler(V5).compileContract(
        """
          | @Callable(i)
          | func default() =
          |   [
          |     ScriptTransfer(Address(base58'3P2pTpQhGbZrJXATKr75A1uZjeTrb4PHMY'), 1, unit)
          |   ]
        """.stripMargin
      )
      d.appendBlock(setScript(secondSigner, dApp))
      d.appendBlockE(invoke()) should produce("Wrong addressBytes length: expected: 26, actual: 25")
    }
  }

  property("bad address checksum") {
    withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      val dApp = TestCompiler(V5).compileContract(
        """
          | @Callable(i)
          | func default() =
          |   [
          |     ScriptTransfer(Address(base58'3DckX66a9NEzR2cGuwHQ4ShAuF2ZSXUdGtS'), 1, unit)
          |   ]
        """.stripMargin
      )
      d.appendBlock(setScript(secondSigner, dApp))
      d.appendBlockE(invoke()) should produce("Bad address checksum")
    }
  }

  property("illegal alias") {
    withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      val dApp = TestCompiler(V5).compileContract(
        """
          | @Callable(i)
          | func default() =
          |   [
          |     ScriptTransfer(Alias("世界冬🤦"), 1, unit)
          |   ]
        """.stripMargin
      )
      d.appendBlock(setScript(secondSigner, dApp))
      d.appendAndAssertFailed(
        invoke(),
        "Alias should contain only following characters: -.0123456789@_abcdefghijklmnopqrstuvwxyz"
      )
    }
  }

  property("amount field absence") {
    withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      val dAppResult = FUNCTION_CALL(
        Native(CREATE_LIST),
        List(
          FUNCTION_CALL(
            User("ScriptTransfer"),
            List(FUNCTION_CALL(User("Alias"), List(CONST_STRING("alias").explicitGet())), REF(GlobalValNames.Unit))
          ),
          REF(GlobalValNames.Nil)
        )
      )
      val dApp =
        ContractScriptImpl(V5, DApp(DAppMeta(), Nil, List(CallableFunction(CallableAnnotation("i"), FUNC("default", Nil, dAppResult))), None))
      val invokeTx = invoke()
      d.appendBlock(setScript(secondSigner, dApp))
      d.appendBlockE(invokeTx)
      d.liquidSnapshot.errorMessage(invokeTx.id()).get.text should include("key not found: asset")
    }
  }

  property("assets from payment are available in ScriptTransfer") {
    val paymentAmount  = 100
    val transferAmount = 99
    val setScriptFee   = FeeConstants(TransactionType.SetScript) * FeeUnit
    withDomain(RideV5, Seq(AddrWithBalance(secondAddress, setScriptFee))) { d =>
      val dApp = TestCompiler(V5).compileContract(
        s"""
           | @Callable(i)
           | func default() =
           |   [
           |     ScriptTransfer(i.caller, $transferAmount, unit)
           |   ]
         """.stripMargin
      )
      d.appendBlock(setScript(secondSigner, dApp))
      d.appendAndAssertSucceed(invoke(payments = Seq(Payment(paymentAmount, Dcc))))
      d.blockchain.balance(secondAddress) shouldBe paymentAmount - transferAmount
    }
  }

  property("invoke fails if Transfer Transaction is prohibited in transfer asset") {
    def test(transferCheck: String) = {
      withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner)) { d =>
        val assetScript = TestCompiler(V5).compileAsset(
          s"""
             | match tx {
             |   case tx: TransferTransaction => $transferCheck
             |   case _                       => true
             | }
           """.stripMargin
        )
        val issueTx = issue(script = Some(assetScript))
        val asset   = IssuedAsset(issueTx.id())
        val dApp    = TestCompiler(V5).compileContract(
          s"""
             | @Callable(i)
             | func default() =
             |   [
             |     ScriptTransfer(i.caller, 1, base58'$asset')
             |   ]
         """.stripMargin
        )
        d.appendBlock(setScript(secondSigner, dApp))
        d.appendBlock(issueTx)
        d.appendAndAssertFailed(invoke(), "Transaction is not allowed by script of the asset")
      }
    }
    test("tx.assetId != this.id")
    test("false")
  }

  property("assets from Reissue are available in ScriptTransfer") {
    withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      val issueAmount    = 100
      val reissueAmount  = 50
      val transferAmount = 120
      val issueTx        = issue(secondSigner, issueAmount)
      val asset          = IssuedAsset(issueTx.id())
      val dApp           = TestCompiler(V5).compileContract(
        s"""
           | @Callable(i)
           | func default() =
           |   [
           |     Reissue(base58'$asset', $reissueAmount, true),
           |     ScriptTransfer(i.caller, $transferAmount, base58'$asset')
           |   ]
         """.stripMargin
      )
      d.appendBlock(issueTx, setScript(secondSigner, dApp))
      d.appendAndAssertSucceed(invoke())
      d.blockchain.balance(secondAddress, asset) shouldBe issueAmount + reissueAmount - transferAmount
    }
  }
}
