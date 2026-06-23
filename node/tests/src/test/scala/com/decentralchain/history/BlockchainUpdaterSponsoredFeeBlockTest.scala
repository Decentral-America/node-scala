package com.decentralchain.history

import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain.BlockchainUpdaterExt
import com.decentralchain.settings.{BlockchainSettings, DCCSettings}
import com.decentralchain.state.*
import com.decentralchain.state.diffs.*
import com.decentralchain.test.*
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.assets.{IssueTransaction, SponsorFeeTransaction}
import com.decentralchain.transaction.transfer.*
import com.decentralchain.transaction.{Asset, GenesisTransaction, TxHelpers, TxVersion}
import org.scalacheck.Gen

class BlockchainUpdaterSponsoredFeeBlockTest extends PropSpec with DomainScenarioDrivenPropertyCheck {
  private val time = new TestTime
  private def ts   = time.getTimestamp()

  private val amtTx = 100000

  type Setup =
    (GenesisTransaction, TransferTransaction, IssueTransaction, SponsorFeeTransaction, TransferTransaction, TransferTransaction, TransferTransaction)

  val sponsorPreconditions: Gen[Setup] = for {

    master                      <- accountGen
    transferAssetDccFee       <- smallFeeGen
    _                           <- accountGen
    alice                       <- accountGen
    bob                         <- accountGen
    (feeAsset, sponsorTx, _, _) <- sponsorFeeCancelSponsorFeeGen(alice)
    dccFee                    = Sponsorship.toDcc(sponsorTx.minSponsoredAssetFee.get.value, sponsorTx.minSponsoredAssetFee.get.value)
    genesis: GenesisTransaction = GenesisTransaction.create(master.toAddress, ENOUGH_AMT, ts).explicitGet()
    masterToAlice: TransferTransaction = TxHelpers.transfer(
      master,
      alice.toAddress,
      feeAsset.fee.value + sponsorTx.fee.value + transferAssetWavesFee + wavesFee,
      Waves,
      transferAssetWavesFee,
      Waves,
      ByteStr.empty,
      ts + 1,
      TxVersion.V1
    )
    aliceToBob: TransferTransaction = TxHelpers.transfer(
      alice,
      bob.toAddress,
      feeAsset.quantity.value / 2,
      Asset.fromCompatId(Some(feeAsset.id())),
      transferAssetWavesFee,
      Waves,
      ByteStr.empty,
      ts + 2,
      TxVersion.V1
    )
    bobToMaster: TransferTransaction = TxHelpers.transfer(
      bob,
      master.toAddress,
      amtTx,
      Asset.fromCompatId(Some(feeAsset.id())),
      sponsorTx.minSponsoredAssetFee.get.value,
      Asset.fromCompatId(Some(feeAsset.id())),
      ByteStr.empty,
      ts + 3,
      TxVersion.V1
    )
    bobToMaster2: TransferTransaction = TxHelpers
      .transfer(
        bob,
        master.toAddress,
        amtTx,
        Asset.fromCompatId(Some(feeAsset.id())),
        sponsorTx.minSponsoredAssetFee.get.value,
        Asset.fromCompatId(Some(feeAsset.id())),
        ByteStr.empty,
        ts + 4,
        TxVersion.V1
      )
  } yield (genesis, masterToAlice, feeAsset, sponsorTx, aliceToBob, bobToMaster, bobToMaster2)

  val SponsoredFeeActivatedAt0BlockchainSettings: BlockchainSettings = DefaultBlockchainSettings.copy(
    functionalitySettings = DefaultBlockchainSettings.functionalitySettings
      .copy(
        featureCheckBlocksPeriod = 1,
        blocksForFeatureActivation = 1,
        preActivatedFeatures = Map(
          BlockchainFeatures.FeeSponsorship.id -> 0,
          BlockchainFeatures.NG.id             -> 0,
          BlockchainFeatures.BlockV5.id        -> 0
        )
      )
  )

  val SponsoredActivatedAt0DCCSettings: DCCSettings = settings.copy(blockchainSettings = SponsoredFeeActivatedAt0BlockchainSettings)

  property("not enough dcc to sponsor sponsored tx") {
    scenario(sponsorPreconditions, SponsoredActivatedAt0DCCSettings) {
      case (d, (genesis, masterToAlice, feeAsset, sponsor, aliceToBob, bobToMaster, bobToMaster2)) =>
        d.appendBlock(genesis)
        d.appendBlock()
        d.appendMicroBlock(masterToAlice)
        d.appendMicroBlock(feeAsset)
        d.appendMicroBlock(sponsor)
        d.appendBlock(aliceToBob, bobToMaster)
        d.appendBlockE(bobToMaster2) should produce("negative dcc balance" /*"unavailable funds"*/ )
    }
  }

  property("calculates valid total fee for microblocks") {
    scenario(sponsorPreconditions, SponsoredActivatedAt0DCCSettings) {
      case (domain, (genesis, masterToAlice, feeAsset, sponsor, aliceToBob, bobToMaster, _)) =>
        val (block0, microBlocks) = chainBaseAndMicro(randomSig, genesis, Seq(Seq(masterToAlice, feeAsset, sponsor), Seq(aliceToBob, bobToMaster)))

        val block0TotalFee = block0.transactionData
          .filter(_.feeAssetId == Dcc)
          .map(_.fee)
          .sum

        {
          domain.blockchainUpdater.processBlock(block0) should beRight
          domain.blockchainUpdater.bestLiquidSnapshotAndFees.map(_._3) should contain(block0TotalFee)
        }

        {
          domain.blockchainUpdater.processMicroBlock(microBlocks(0), None) should beRight
          domain.blockchainUpdater.processMicroBlock(microBlocks(1), None) should beRight

          val microBlocksDccFee = microBlocks
            .flatMap(_.transactionData)
            .map(tx => Sponsorship.calcDccFeeAmount(tx, ai => domain.blockchainUpdater.assetDescription(ai).map(_.sponsorship)))
            .sum

          domain.blockchainUpdater.bestLiquidSnapshotAndFees.map(_._3) should contain(block0TotalFee + microBlocksDccFee)
        }
    }
  }

}
