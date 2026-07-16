package com.decentralchain.history

import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain.BlockchainUpdaterExt
import com.decentralchain.settings.{BlockchainSettings, DCCSettings}
import com.decentralchain.state.diffs.ENOUGH_AMT
import com.decentralchain.test.*
import com.decentralchain.transaction.assets.{BurnTransaction, IssueTransaction, ReissueTransaction}
import com.decentralchain.transaction.transfer.TransferTransaction
import com.decentralchain.transaction.{Asset, GenesisTransaction, TxHelpers, TxVersion}
import org.scalacheck.Gen

class BlockchainUpdaterBurnTest extends PropSpec with DomainScenarioDrivenPropertyCheck {
  val Dcc: Long = 100000000

  type Setup =
    (Long, GenesisTransaction, TransferTransaction, IssueTransaction, BurnTransaction, ReissueTransaction)

  val preconditions: Gen[Setup] = for {
    master                                                   <- accountGen
    ts                                                       <- timestampGen
    transferAssetDccFee                                      <- smallFeeGen
    alice                                                    <- accountGen
    (_, assetName, description, quantity, decimals, _, _, _) <- issueParamGen
    genesis: GenesisTransaction        = GenesisTransaction.create(master.toAddress, ENOUGH_AMT, ts).explicitGet()
    masterToAlice: TransferTransaction = TxHelpers.transfer(
      from = master,
      to = alice.toAddress,
      amount = 3 * Dcc,
      asset = Asset.Dcc,
      fee = transferAssetDccFee,
      feeAsset = Asset.Dcc,
      attachment = ByteStr.empty,
      timestamp = ts + 1,
      version = 1.toByte
    )
    issue: IssueTransaction = TxHelpers.issue(
      issuer = alice,
      amount = quantity,
      decimals = decimals,
      name = new String(assetName),
      description = new String(description),
      fee = Dcc,
      script = None,
      reissuable = false,
      timestamp = ts + 100,
      version = TxVersion.V1
    )
    burn: BurnTransaction       = BurnTransaction.selfSigned(1.toByte, alice, issue.asset, quantity / 2, Dcc, ts + 200).explicitGet()
    reissue: ReissueTransaction = ReissueTransaction
      .selfSigned(1.toByte, alice, issue.asset, burn.quantity.value, reissuable = true, Dcc, ts + 300)
      .explicitGet()
  } yield (ts, genesis, masterToAlice, issue, burn, reissue)

  val localBlockchainSettings: BlockchainSettings = DefaultBlockchainSettings.copy(
    functionalitySettings = DefaultBlockchainSettings.functionalitySettings
      .copy(
        featureCheckBlocksPeriod = 1,
        blocksForFeatureActivation = 1,
        preActivatedFeatures = Map(BlockchainFeatures.NG.id -> 0, BlockchainFeatures.DataTransaction.id -> 0)
      )
  )
  val localDCCSettings: DCCSettings = settings.copy(blockchainSettings = localBlockchainSettings)

  property("issue -> burn -> reissue in sequential blocks works correctly") {
    scenario(preconditions, localDCCSettings) { case (domain, (ts, genesis, masterToAlice, issue, burn, reissue)) =>
      val block0 = customBuildBlockOfTxs(randomSig, Seq(genesis), defaultSigner, 1.toByte, ts)
      val block1 = customBuildBlockOfTxs(block0.id(), Seq(masterToAlice), defaultSigner, TxVersion.V1, ts + 150)
      val block2 = customBuildBlockOfTxs(block1.id(), Seq(issue), defaultSigner, TxVersion.V1, ts + 250)
      val block3 = customBuildBlockOfTxs(block2.id(), Seq(burn), defaultSigner, TxVersion.V1, ts + 350)
      val block4 = customBuildBlockOfTxs(block3.id(), Seq(reissue), defaultSigner, TxVersion.V1, ts + 450)

      domain.appendBlock(block0)
      domain.appendBlock(block1)

      domain.appendBlock(block2)
      val assetDescription1 = domain.blockchainUpdater.assetDescription(issue.asset).get
      assetDescription1.reissuable should be(false)
      assetDescription1.totalVolume should be(issue.quantity.value)

      domain.appendBlock(block3)
      val assetDescription2 = domain.blockchainUpdater.assetDescription(issue.asset).get
      assetDescription2.reissuable should be(false)
      assetDescription2.totalVolume should be(issue.quantity.value - burn.quantity.value)

      domain.blockchainUpdater.processBlock(block4) should produce("Asset is not reissuable")
    }
  }

  property("issue -> burn -> reissue in micro blocks works correctly") {
    scenario(preconditions, localDCCSettings) { case (domain, (ts, genesis, masterToAlice, issue, burn, reissue)) =>
      val block0 = customBuildBlockOfTxs(randomSig, Seq(genesis), defaultSigner, TxVersion.V1, ts)
      val block1 = customBuildBlockOfTxs(block0.id(), Seq(masterToAlice), defaultSigner, TxVersion.V1, ts + 150)
      val block2 = customBuildBlockOfTxs(block1.id(), Seq(issue), defaultSigner, TxVersion.V1, ts + 250)
      val block3 = customBuildBlockOfTxs(block2.id(), Seq(burn, reissue), defaultSigner, TxVersion.V1, ts + 350)

      domain.appendBlock(block0)
      domain.appendBlock(block1)

      domain.appendBlock(block2)
      val assetDescription1 = domain.blockchainUpdater.assetDescription(issue.asset).get
      assetDescription1.reissuable should be(false)
      assetDescription1.totalVolume should be(issue.quantity.value)

      domain.blockchainUpdater.processBlock(block3) should produce("Asset is not reissuable")
    }
  }
}
