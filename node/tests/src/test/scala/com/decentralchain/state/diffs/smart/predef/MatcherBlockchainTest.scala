package com.decentralchain.state.diffs.smart.predef

import com.decentralchain.account.{Address, Alias}
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.SignedBlockHeader
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.BlsPublicKey
import com.decentralchain.db.WithDomain
import com.decentralchain.lang.ValidationError
import com.decentralchain.lang.directives.values.V5
import com.decentralchain.lang.v1.compiler.Terms.CONST_BOOLEAN
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.lang.v1.traits.domain.Recipient
import com.decentralchain.settings.BlockchainSettings
import com.decentralchain.state.*
import com.decentralchain.state.TxMeta.Status
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.smart.script.ScriptRunner
import com.decentralchain.transaction.transfer.TransferTransaction
import com.decentralchain.transaction.{Asset, ERC20Address, Transaction, TxHelpers}

class MatcherBlockchainTest extends PropSpec, WithDomain {
  property("ScriptRunner.applyGeneric() avoids Blockchain calls") {
    val blockchain: Blockchain = new Blockchain {
      override def settings: BlockchainSettings                                                             = ???
      override def height: Int                                                                              = ???
      override def finalizedHeight: Option[Height]                                                          = ???
      override def finalizedHeightAt(at: Height): Option[Height]                                            = ???
      override def score: BigInt                                                                            = ???
      override def blockHeader(height: Int): Option[SignedBlockHeader]                                      = ???
      override def hitSource(height: Int): Option[ByteStr]                                                  = ???
      override def carryFee(refId: Option[ByteStr]): Long                                                   = ???
      override def heightOf(blockId: ByteStr): Option[Int]                                                  = ???
      override def approvedFeatures: Map[Short, Height]                                                     = ???
      override def activatedFeatures: Map[Short, Height]                                                    = ???
      override def featureVotes(height: Height): Map[Short, Int]                                            = ???
      override def blockReward(height: Int): Option[Long]                                                   = ???
      override def blockRewardVotes(height: Int): Seq[Long]                                                 = ???
      override def dccAmount(height: Int): BigInt                                                         = ???
      override def transferById(id: ByteStr): Option[(Int, TransferTransaction)]                            = ???
      override def transactionInfo(id: ByteStr): Option[(TxMeta, Transaction)]                              = ???
      override def transactionInfos(ids: Seq[BlockId]): Seq[Option[(TxMeta, Transaction)]]                  = ???
      override def transactionMeta(id: ByteStr): Option[TxMeta]                                             = ???
      override def transactionSnapshot(id: ByteStr): Option[(StateSnapshot, Status)]                        = ???
      override def containsTransaction(tx: Transaction): Boolean                                            = ???
      override def assetDescription(id: Asset.IssuedAsset): Option[AssetDescription]                        = ???
      override def resolveAlias(a: Alias): Either[ValidationError, Address]                                 = ???
      override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails]                                     = ???
      override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee                                       = ???
      override def balanceAtHeight(address: Address, height: Int, assetId: Asset): Option[(Int, Long)]      = ???
      override def balanceSnapshots(address: Address, from: Int, to: Option[BlockId]): Seq[BalanceSnapshot] = ???
      override def accountScript(address: Address): Option[AccountScriptInfo]                               = ???
      override def hasAccountScript(address: Address): Boolean                                              = ???
      override def assetScript(id: Asset.IssuedAsset): Option[AssetScriptInfo]                              = ???
      override def accountData(acc: Address, key: String): Option[DataEntry[?]]                             = ???
      override def hasData(address: Address): Boolean                                                       = ???
      override def leaseBalance(address: Address): LeaseBalance                                             = ???
      override def leaseBalances(addresses: Seq[Address]): Map[Address, LeaseBalance]                       = ???
      override def balance(address: Address, mayBeAssetId: Asset): Long                                     = ???
      override def balances(req: Seq[(Address, Asset)]): Map[(Address, Asset), Long]                        = ???
      override def dccBalances(addresses: Seq[Address]): Map[Address, Long]                               = ???
      override def effectiveBalanceBanHeights(address: Address): Seq[Int]                                   = ???
      override def resolveERC20Address(address: ERC20Address): Option[Asset.IssuedAsset]                    = ???
      override def lastStateHash(refId: Option[ByteStr]): BlockId                                           = ???
      override def committedGenerators(at: GenerationPeriod): IndexedSeq[(Address, BlsPublicKey)]           = ???
      override def conflictGenerators(at: GenerationPeriod): ConflictGenerators                             = ???
    }

    val tx = TxHelpers.transfer(
      from = accountGen.sample.get,
      to = accountGen.sample.get.toAddress,
      amount = 1,
      asset = Waves,
      fee = 1,
      feeAsset = Waves,
      attachment = ByteStr.empty,
      timestamp = 0,
      version = 1.toByte
    )
    val scripts =
      Seq(
        TestCompiler(V5).compileExpression("true"),
        TestCompiler(V5).compileContract(
          """
            |@Callable(i)
            |func foo() = []
            |""".stripMargin
        ),
        TestCompiler(V5).compileContract(
          """
            |@Callable(i)
            |func foo() = []
            |
            |@Verifier(tx)
            |func bar() = true
            |""".stripMargin
        )
      )

    scripts.foreach { script =>
      ScriptRunner
        .applyGeneric(
          tx,
          blockchain,
          script,
          isAssetScript = false,
          Recipient.Address(ByteStr.empty),
          defaultLimit = 2000,
          default = null,
          useCorrectScriptVersion = true,
          fixUnicodeFunctions = true,
          useNewPowPrecision = true,
          checkEstimatorSumOverflow = true,
          newEvaluatorMode = true,
          checkWeakPk = true,
          enableExecutionLog = false,
          fixBigScriptField = true,
          fixedThrownError = true,
          fixEcrecover = true
        )
        ._3 shouldBe Right(CONST_BOOLEAN(true))
    }
  }
}
