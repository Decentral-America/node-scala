package com.decentralchain.events

import com.decentralchain.TestValues.fee
import com.decentralchain.account.{Address, SeedKeyPair}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.events.fixtures.BlockchainUpdateGrpcMethod.*
import com.decentralchain.events.fixtures.InvokeDccTxCheckers.checkInvokeDoubleNestedBlockchainUpdates
import com.decentralchain.events.fixtures.PrepareInvokeTestData.*
import com.decentralchain.events.fixtures.DccTxChecks.*
import io.decentralchain.events.protobuf.BlockchainUpdated.Append
import com.decentralchain.lang.v1.compiler.Terms.{CONST_BYTESTR, CONST_LONG, CONST_STRING, EXPR}
import com.decentralchain.test.NumericExt
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.TxHelpers.secondAddress
import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.smart.InvokeScriptTransaction
import com.decentralchain.transaction.{Asset, TxHelpers}

class BlockchainUpdatesInvokeTxSpec extends BlockchainUpdatesTestBase {
  "Simple invoke transaction" - {
    val issue           = TxHelpers.issue(firstTxParticipant)
    val asset: Asset    = issue.asset
    val assetByteStr    = CONST_BYTESTR(asset.compatId.get).explicitGet()
    val addressByteStr  = CONST_BYTESTR(ByteStr.apply(secondTxParticipantAddress.bytes)).explicitGet()
    val args: Seq[EXPR] = Seq(assetByteStr, addressByteStr)
    val invoke = TxHelpers.invoke(firstTxParticipantAddress, Some(invokeFunctionName), args, Seq.empty, secondTxParticipant, fee = 100500000L)
    val issuerAssetBalanceAfterTx: Long = issue.quantity.value - burnNum - scriptTransferAssetNum + reissueNum
    val senderDccBalanceAfterTx: Long   = secondTxParticipantBalanceBefore - invoke.fee.value + scriptTransferUnitNum
    val issuerBalanceBeforeInvoke: Long = firstTxParticipantBalanceBefore - issue.fee.value - fee
    val issuerBalanceAfterInvoke: Long  = issuerBalanceBeforeInvoke - scriptTransferUnitNum
    val balances                        = Seq(
      AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore),
      AddrWithBalance(secondTxParticipantAddress, secondTxParticipantBalanceBefore)
    )
    val expectBalanceMap = Map(
      (secondTxParticipantAddress, Dcc)   -> (secondTxParticipantBalanceBefore, senderDccBalanceAfterTx),
      (secondTxParticipantAddress, asset) -> (0L, scriptTransferAssetNum),
      (firstTxParticipantAddress, Dcc)    -> (issuerBalanceBeforeInvoke, issuerBalanceAfterInvoke),
      (firstTxParticipantAddress, asset)  -> (issue.quantity.value, issuerAssetBalanceAfterTx)
    )

    "BU-31. Invoke have to return correct data for subscribe" in {
      testInvoke(issue, invoke, balances)(
        Subscribe,
        checkFunction = append => {
          val invokeScriptMetadata = append.transactionsMetadata.head.getInvokeScript
          checkInvokeBase(append, invoke)
          checkGeneralInvoke(append, issuerAssetBalanceAfterTx, invoke, issue, invokeScriptMetadata, expectBalanceMap)
        }
      )
    }

    "BU-208. Invoke have to return correct data for getBlockUpdate" in {
      testInvoke(issue, invoke, balances)(
        GetBlockUpdate,
        checkFunction = append => {
          val invokeScriptMetadata = append.transactionsMetadata.head.getInvokeScript
          checkInvokeBase(append, invoke)
          checkGeneralInvoke(append, issuerAssetBalanceAfterTx, invoke, issue, invokeScriptMetadata, expectBalanceMap)
        }
      )
    }

    "BU-173. Invoke have to return correct data for getBlockUpdateRange" in {
      testInvoke(issue, invoke, balances)(
        GetBlockUpdateRange,
        checkFunction = append => {
          val invokeScriptMetadata = append.transactionsMetadata.head.getInvokeScript
          checkInvokeBase(append, invoke)
          checkGeneralInvoke(append, issuerAssetBalanceAfterTx, invoke, issue, invokeScriptMetadata, expectBalanceMap)
        }
      )
    }
  }

  "Double nesting call tests" - {
    val assetDappAccount: SeedKeyPair   = TxHelpers.signer(4)
    val assetDappAddress: Address       = assetDappAccount.toAddress
    val invokerDappAccount: SeedKeyPair = TxHelpers.signer(5)
    val invokerDappAddress: Address     = invokerDappAccount.toAddress
    val issue: IssueTransaction         = TxHelpers.issue(assetDappAccount)
    val asset: Asset                    = issue.asset
    val issueAssetFee                   = issue.fee.value
    val massTx                          = TxHelpers.massTransfer(
      assetDappAccount,
      Seq(
        firstTxParticipantAddress -> amount,
        secondAddress             -> amount,
        invokerDappAddress        -> amount
      ),
      asset,
      fee = 500000
    )
    val args: Seq[EXPR] =
      Seq(
        CONST_BYTESTR(ByteStr.apply(secondAddress.bytes)).explicitGet(),
        CONST_BYTESTR(ByteStr.apply(assetDappAddress.bytes)).explicitGet(),
        CONST_LONG(scriptTransferUnitNum),
        CONST_STRING(bar).explicitGet(),
        CONST_BYTESTR(asset.compatId.get).explicitGet()
      )
    val invoke                     = TxHelpers.invoke(firstTxParticipantAddress, Some(foo), args, Seq.empty, invokerDappAccount, fee = 100500000L)
    val invokerDappBalance: Long   = 4.dcc
    val secondAddressBalance: Long = 8.dcc
    val assetDappBalance: Long     = 12.dcc
    val secondAddressAssetBalanceForAll: Long               = amount - scriptTransferAssetNum + paymentNum
    val dAppAddressAssetBalanceForCaller: Long              = amount + scriptTransferAssetNum - paymentNum
    val invokerDappAddressAssetBalanceForOriginCaller: Long = amount + scriptTransferAssetNum
    val dAppAddressAssetBalanceForOriginCaller: Long        = amount - paymentNum
    val secondAddressDccBalanceBefore: Long                 = secondAddressBalance - fee
    val secondAddressDccBalanceAfter: Long                  = secondAddressDccBalanceBefore + scriptTransferUnitNum
    val assetDappAddressDccBalanceBefore: Long              = assetDappBalance - issueAssetFee - massTx.fee.value - fee
    val assetDappAddressDccBalanceAfter: Long               = assetDappAddressDccBalanceBefore - scriptTransferUnitNum
    val invokerDappAddressDccBalance: Long                  = invokerDappBalance - invoke.fee.value
    val balancesSeq                                         = Seq(
      AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore),
      AddrWithBalance(secondAddress, secondAddressBalance),
      AddrWithBalance(invokerDappAddress, invokerDappBalance),
      AddrWithBalance(assetDappAddress, assetDappBalance)
    )
    val callerBalancesMap = Map(
      (secondAddress, Dcc)               -> (secondAddressDccBalanceBefore, secondAddressDccBalanceAfter),
      (secondAddress, asset)             -> (amount, secondAddressAssetBalanceForAll),
      (firstTxParticipantAddress, asset) -> (amount, dAppAddressAssetBalanceForCaller),
      (assetDappAddress, Dcc)            -> (assetDappAddressDccBalanceBefore, assetDappAddressDccBalanceAfter),
      (invokerDappAddress, Dcc)          -> (invokerDappBalance, invokerDappAddressDccBalance)
    )
    val originalCallerBalancesMap = Map(
      (invokerDappAddress, asset)        -> (amount, invokerDappAddressAssetBalanceForOriginCaller),
      (secondAddress, asset)             -> (amount, secondAddressAssetBalanceForAll),
      (firstTxParticipantAddress, asset) -> (amount, dAppAddressAssetBalanceForOriginCaller),
      (assetDappAddress, Dcc)            -> (assetDappAddressDccBalanceBefore, assetDappAddressDccBalanceAfter),
      (invokerDappAddress, Dcc)          -> (invokerDappBalance, invokerDappAddressDccBalance + scriptTransferUnitNum)
    )

    "BU-77. doubles nested i.caller. Invoke have to return correct data for subscribe" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, caller, Subscribe) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getInvokeScript
        checkInvokeBase(append, invoke)
        checkInvokeDoubleNestedBlockchainUpdates(
          append,
          invokeScriptMetadata,
          assetDappAddress,
          firstTxParticipantAddress,
          secondAddress,
          issue,
          callerBalancesMap
        )
      }
    }

    "BU-210. doubles nested i.caller. Invoke have to return correct data for getBlockUpdate" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, caller, GetBlockUpdate) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getInvokeScript
        checkInvokeBase(append, invoke)
        checkInvokeDoubleNestedBlockchainUpdates(
          append,
          invokeScriptMetadata,
          assetDappAddress,
          firstTxParticipantAddress,
          secondAddress,
          issue,
          callerBalancesMap
        )
      }
    }

    "BU-175. doubles nested i.caller. Invoke have to return correct data for getBlockUpdateRange" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, caller, GetBlockUpdateRange) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getInvokeScript
        checkInvokeBase(append, invoke)
        checkInvokeDoubleNestedBlockchainUpdates(
          append,
          invokeScriptMetadata,
          assetDappAddress,
          firstTxParticipantAddress,
          secondAddress,
          issue,
          callerBalancesMap
        )
      }
    }

    "BU-39. double nested i.originCaller. Invoke have to return correct data for subscribe" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, originCaller, Subscribe) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getInvokeScript
        checkInvokeBase(append, invoke)
        checkInvokeDoubleNestedBlockchainUpdates(
          append,
          invokeScriptMetadata,
          assetDappAddress,
          invokerDappAddress,
          invokerDappAddress,
          issue,
          originalCallerBalancesMap
        )
      }
    }

    "BU-209. doubles nested i.originCaller. Invoke have to return correct data for getBlockUpdate" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, originCaller, GetBlockUpdate) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getInvokeScript
        checkInvokeBase(append, invoke)
        checkInvokeDoubleNestedBlockchainUpdates(
          append,
          invokeScriptMetadata,
          assetDappAddress,
          invokerDappAddress,
          invokerDappAddress,
          issue,
          originalCallerBalancesMap
        )
      }
    }

    "BU-174. doubles nested i.originCaller. Invoke have to return correct data for getBlockUpdateRange" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, originCaller, GetBlockUpdateRange) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getInvokeScript
        checkInvokeBase(append, invoke)
        checkInvokeDoubleNestedBlockchainUpdates(
          append,
          invokeScriptMetadata,
          assetDappAddress,
          invokerDappAddress,
          invokerDappAddress,
          issue,
          originalCallerBalancesMap
        )
      }
    }
  }

  def checkInvokeBase(append: Append, invoke: InvokeScriptTransaction): Unit = {
    val transactionMetadata = append.transactionsMetadata.head
    checkInvokeTransaction(append.transactionIds.head, append.transactionAt(0), invoke, firstTxParticipantAddress.publicKeyHash)
    checkInvokeBaseTransactionMetadata(transactionMetadata, invoke)
  }
}
