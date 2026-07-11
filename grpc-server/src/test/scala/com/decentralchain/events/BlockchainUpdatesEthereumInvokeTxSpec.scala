package com.decentralchain.events

import com.decentralchain.TestValues.fee
import com.decentralchain.account.{Address, SeedKeyPair}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.events.fixtures.BlockchainUpdateGrpcMethod.*
import com.decentralchain.events.fixtures.InvokeDccTxCheckers.checkInvokeDoubleNestedBlockchainUpdates
import com.decentralchain.events.fixtures.PrepareInvokeTestData.*
import com.decentralchain.events.fixtures.DccTxChecks.*
import io.decentralchain.events.protobuf.BlockchainUpdated.Append
import com.decentralchain.test.NumericExt
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.EthTxGenerator.Arg
import com.decentralchain.transaction.TxHelpers.secondAddress
import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.{Asset, EthTxGenerator, EthereumTransaction, TxHelpers}

class BlockchainUpdatesEthereumInvokeTxSpec extends BlockchainUpdatesTestBase {
  val ethAddressBalanceAfterTx: Long = firstTxParticipantBalanceBefore - invokeFee

  "Simple invoke transaction" - {
    val issue                       = TxHelpers.issue(firstTxParticipant)
    val asset: Asset                = issue.asset
    val assetByteStr                = Arg.Bytes(asset.compatId.get)
    val addressByteStr              = Arg.Bytes(ByteStr.apply(secondTxParticipantAddress.bytes))
    val args: Seq[Arg]              = Seq(assetByteStr, addressByteStr)
    val invoke: EthereumTransaction =
      EthTxGenerator.generateEthInvoke(firstTxParticipantEthereum, firstTxParticipantAddress, invokeFunctionName, args, Seq.empty, invokeFee)
    val issuerAssetBalanceAfterTx: Long      = issue.quantity.value - burnNum - scriptTransferAssetNum + reissueNum
    val secondAddressDccBalanceAfterTx: Long = secondTxParticipantBalanceBefore + scriptTransferUnitNum
    val issuerBalanceBeforeInvoke: Long      = firstTxParticipantBalanceBefore - issue.fee.value - fee
    val balances: Seq[AddrWithBalance]       = Seq(
      AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore),
      AddrWithBalance(firstTxParticipantEthereumAddress, firstTxParticipantBalanceBefore),
      AddrWithBalance(secondTxParticipantAddress, secondTxParticipantBalanceBefore)
    )
    val expectBalanceMap = Map(
      (firstTxParticipantEthereumAddress, Dcc) -> (firstTxParticipantBalanceBefore, ethAddressBalanceAfterTx),
      (firstTxParticipantAddress, Dcc)         -> (issuerBalanceBeforeInvoke, issuerBalanceBeforeInvoke - scriptTransferUnitNum),
      (secondTxParticipantAddress, Dcc)        -> (secondTxParticipantBalanceBefore, secondAddressDccBalanceAfterTx),
      (firstTxParticipantAddress, asset)       -> (issue.quantity.value, issuerAssetBalanceAfterTx),
      (secondTxParticipantAddress, asset)      -> (0L, scriptTransferAssetNum)
    )

    "BU-226. Invoke have to return correct data for subscribe" in {
      testInvoke(issue, invoke, balances)(
        Subscribe,
        checkFunction = append => {
          val invokeScriptMetadata = append.transactionsMetadata.head.getEthereum.getInvoke
          checkEthereumBase(append, invoke, invokeFunctionName)
          checkGeneralInvoke(append, issuerAssetBalanceAfterTx, invoke, issue, invokeScriptMetadata, expectBalanceMap)
        }
      )
    }

    "BU-229. Invoke have to return correct data for getBlockUpdate" in {
      testInvoke(issue, invoke, balances)(
        GetBlockUpdate,
        checkFunction = append => {
          val invokeScriptMetadata = append.transactionsMetadata.head.getEthereum.getInvoke
          checkEthereumBase(append, invoke, invokeFunctionName)
          checkGeneralInvoke(append, issuerAssetBalanceAfterTx, invoke, issue, invokeScriptMetadata, expectBalanceMap)
        }
      )
    }

    "BU-232. Invoke have to return correct data for getBlockUpdateRange" in {
      testInvoke(issue, invoke, balances)(
        GetBlockUpdateRange,
        checkFunction = append => {
          val invokeScriptMetadata = append.transactionsMetadata.head.getEthereum.getInvoke
          checkEthereumBase(append, invoke, invokeFunctionName)
          checkGeneralInvoke(append, issuerAssetBalanceAfterTx, invoke, issue, invokeScriptMetadata, expectBalanceMap)
        }
      )
    }
  }

  "Double nesting call ethereum tests" - {
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
    val args: Seq[Arg] =
      Seq(
        Arg.Bytes(ByteStr.apply(secondAddress.bytes)),
        Arg.Bytes(ByteStr.apply(assetDappAddress.bytes)),
        Arg.Integer(scriptTransferUnitNum),
        Arg.Str(bar),
        Arg.Bytes(asset.compatId.get)
      )
    val invoke = EthTxGenerator.generateEthInvoke(firstTxParticipantEthereum, firstTxParticipantAddress, "foo", args, Seq.empty, invokeFee)
    val secondAddressBalance: Long               = 8.dcc
    val assetDappBalance: Long                   = 12.dcc
    val secondAddressAssetBalanceForAll          = amount - scriptTransferAssetNum + paymentNum
    val dAppAddressAssetBalanceForCaller         = amount + scriptTransferAssetNum - paymentNum
    val dAppAddressAssetBalanceForOriginalCaller = amount - paymentNum
    val balancesSeq                              = Seq(
      AddrWithBalance(firstTxParticipantAddress, firstTxParticipantBalanceBefore),
      AddrWithBalance(firstTxParticipantEthereumAddress, firstTxParticipantBalanceBefore),
      AddrWithBalance(secondAddress, secondAddressBalance),
      AddrWithBalance(invokerDappAddress, ethAddressBalanceAfterTx),
      AddrWithBalance(assetDappAddress, assetDappBalance)
    )
    val secondAddressDccBalanceBefore    = secondAddressBalance - fee
    val secondAddressDccBalanceAfter     = secondAddressDccBalanceBefore + scriptTransferUnitNum
    val assetDappAddressDccBalanceBefore = assetDappBalance - issueAssetFee - massTx.fee.value - fee
    val assetDappAddressDccBalanceAfter  = assetDappAddressDccBalanceBefore - scriptTransferUnitNum
    val firstTxParticipantBalanceAfter   = ethAddressBalanceAfterTx + scriptTransferUnitNum
    val callerBalancesMap                = Map(
      (secondAddress, Dcc)                     -> (secondAddressDccBalanceBefore, secondAddressDccBalanceAfter),
      (secondAddress, asset)                   -> (amount, secondAddressAssetBalanceForAll),
      (firstTxParticipantAddress, asset)       -> (amount, dAppAddressAssetBalanceForCaller),
      (assetDappAddress, Dcc)                  -> (assetDappAddressDccBalanceBefore, assetDappAddressDccBalanceAfter),
      (firstTxParticipantEthereumAddress, Dcc) -> (firstTxParticipantBalanceBefore, ethAddressBalanceAfterTx)
    )
    val originalCallerBalancesMap = Map(
      (secondAddress, asset)                     -> (amount, secondAddressAssetBalanceForAll),
      (firstTxParticipantEthereumAddress, asset) -> (0L, scriptTransferAssetNum),
      (firstTxParticipantAddress, asset)         -> (amount, dAppAddressAssetBalanceForOriginalCaller),
      (assetDappAddress, Dcc)                    -> (assetDappAddressDccBalanceBefore, assetDappAddressDccBalanceAfter),
      (firstTxParticipantEthereumAddress, Dcc)   -> (firstTxParticipantBalanceBefore, firstTxParticipantBalanceAfter)
    )

    "BU-227. doubles nested i.caller. Invoke have to return correct data for subscribe" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, caller, Subscribe) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getEthereum.getInvoke
        checkEthereumBase(append, invoke, foo)
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

    "BU-230. doubles nested i.caller. Invoke have to return correct data for getBlockUpdate" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, caller, GetBlockUpdate) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getEthereum.getInvoke
        checkEthereumBase(append, invoke, foo)
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

    "BU-233. doubles nested i.caller. Invoke have to return correct data for getBlockUpdateRange" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, caller, GetBlockUpdateRange) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getEthereum.getInvoke
        checkEthereumBase(append, invoke, foo)
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

    "BU-228. double nested i.originCaller. Invoke have to return correct data for subscribe" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, originCaller, Subscribe) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getEthereum.getInvoke
        checkEthereumBase(append, invoke, foo)
        checkInvokeDoubleNestedBlockchainUpdates(
          append,
          invokeScriptMetadata,
          assetDappAddress,
          firstTxParticipantEthereumAddress,
          firstTxParticipantEthereumAddress,
          issue,
          originalCallerBalancesMap
        )
      }
    }

    "BU-231. double nested i.originCaller. Invoke have to return correct data for getBlockUpdate" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, originCaller, GetBlockUpdate) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getEthereum.getInvoke
        checkEthereumBase(append, invoke, foo)
        checkInvokeDoubleNestedBlockchainUpdates(
          append,
          invokeScriptMetadata,
          assetDappAddress,
          firstTxParticipantEthereumAddress,
          firstTxParticipantEthereumAddress,
          issue,
          originalCallerBalancesMap
        )
      }
    }

    "BU-234. double nested i.originCaller. Invoke have to return correct data for getBlockUpdateRange" in {
      doubleNestedInvokeTest(assetDappAccount, balancesSeq, issue, invoke, massTx, originCaller, GetBlockUpdateRange) { append =>
        val invokeScriptMetadata = append.transactionsMetadata.head.getEthereum.getInvoke
        checkEthereumBase(append, invoke, foo)
        checkInvokeDoubleNestedBlockchainUpdates(
          append,
          invokeScriptMetadata,
          assetDappAddress,
          firstTxParticipantEthereumAddress,
          firstTxParticipantEthereumAddress,
          issue,
          originalCallerBalancesMap
        )
      }
    }
  }

  def checkEthereumBase(append: Append, invoke: EthereumTransaction, functionName: String): Unit = {
    val txMetadata = append.transactionsMetadata.head
    checkEthereumTransaction(append.transactionIds.head, append.transactionAt(0), invoke)
    checkEthereumInvokeBaseTransactionMetadata(txMetadata, invoke, functionName, firstTxParticipantAddress)
  }
}
