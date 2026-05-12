package com.decentralchain.state.diffs.smart.eth

import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.history.Domain
import com.decentralchain.lang.directives.values.V5
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import com.decentralchain.test.*
import com.decentralchain.transaction.{Asset, EthTxGenerator}
import com.decentralchain.transaction.Asset.{IssuedAsset, Waves}
import com.decentralchain.transaction.TransactionType.Transfer
import com.decentralchain.transaction.TxHelpers.*
import com.decentralchain.transaction.utils.EthConverters.*
import com.decentralchain.utils.EthHelpers

class EthereumTransferFeeTest extends PropSpec with WithDomain with EthHelpers {
  import DomainPresets.*

  private val transferFee      = FeeConstants(Transfer) * FeeUnit
  private val transferSmartFee = transferFee + ScriptExtraFee

  property("smart asset should require additional fee") {
    val assetScript = TestCompiler(V5).compileExpression("true")
    val issueTx     = issue(script = Some(assetScript))
    val asset       = IssuedAsset(issueTx.id())
    val preTransfer = transfer(to = defaultSigner.toEthWavesAddress, asset = asset)
    withDomain(RideV6, Seq(AddrWithBalance(defaultSigner.toEthWavesAddress))) { d =>
      d.appendBlock(issueTx, preTransfer)
      assertMinFee(d, asset, transferSmartFee)
    }
  }

  property("non-smart asset should require standard fee") {
    val issueTx     = issue()
    val asset       = IssuedAsset(issueTx.id())
    val preTransfer = transfer(to = defaultSigner.toEthWavesAddress, asset = asset)
    withDomain(RideV6, Seq(AddrWithBalance(defaultSigner.toEthWavesAddress))) { d =>
      d.appendBlock(issueTx, preTransfer)
      assertMinFee(d, asset, transferFee)
    }
  }

  property("Waves should require standard fee") {
    withDomain(RideV6, Seq(AddrWithBalance(defaultSigner.toEthWavesAddress))) { d =>
      assertMinFee(d, Waves, transferFee)
    }
  }

  private def assertMinFee(d: Domain, asset: Asset, fee: Long) = {
    val notEnoughFeeTx = EthTxGenerator.generateEthTransfer(defaultSigner.toEthKeyPair, secondAddress, 1, asset, fee = fee - 1)
    val enoughFeeTx    = EthTxGenerator.generateEthTransfer(defaultSigner.toEthKeyPair, secondAddress, 1, asset, fee = fee)
    d.appendBlockE(notEnoughFeeTx) should produce(s"does not exceed minimal value of $fee DCC")
    d.appendAndAssertSucceed(enoughFeeTx)
  }
}
