package com.decentralchain

import com.typesafe.config.ConfigFactory
import com.decentralchain.account.KeyPair
import com.decentralchain.block.{Block, MicroBlock}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.features.{BlockchainFeature, BlockchainFeatures}
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.settings.*
import com.decentralchain.transaction.Transaction

package object history {
  val MaxTransactionsPerBlockDiff = 10
  val MaxBlocksInMemory           = 5
  val DefaultBaseTarget           = 1000L
  val DefaultBlockchainSettings = BlockchainSettings(
    addressSchemeCharacter = 'N',
    functionalitySettings = TestFunctionalitySettings.Enabled,
    genesisSettings = GenesisSettings.TESTNET,
    rewardsSettings = RewardsSettings.TESTNET
  )

  val config   = ConfigFactory.load()
  val settings = DCCSettings.fromRootConfig(config)

  val MicroblocksActivatedAt0DCCSettings: DCCSettings        = settingsWithFeatures(BlockchainFeatures.NG)
  val DataAndMicroblocksActivatedAt0DCCSettings: DCCSettings = settingsWithFeatures(BlockchainFeatures.DataTransaction, BlockchainFeatures.NG)
  val TransfersV2ActivatedAt0DCCSettings: DCCSettings        = settingsWithFeatures(BlockchainFeatures.SmartAccounts)

  def settingsWithFeatures(features: BlockchainFeature*): DCCSettings = {
    val blockchainSettings = DefaultBlockchainSettings.copy(
      functionalitySettings = DefaultBlockchainSettings.functionalitySettings.copy(preActivatedFeatures = features.map(_.id -> 0).toMap)
    )
    settings.copy(blockchainSettings = blockchainSettings)
  }

  val DefaultDCCSettings: DCCSettings = settings.copy(
    blockchainSettings = DefaultBlockchainSettings,
    featuresSettings = settings.featuresSettings.copy(autoShutdownOnUnsupportedFeature = false)
  )

  val defaultSigner          = TestValues.keyPair
  val generationSignature    = ByteStr(new Array[Byte](Block.GenerationSignatureLength))
  val generationVRFSignature = ByteStr(new Array[Byte](Block.GenerationVRFSignatureLength))

  def correctGenerationSignature(version: Byte): ByteStr = if (version < Block.ProtoBlockVersion) generationSignature else generationVRFSignature

  def buildBlockOfTxs(refTo: ByteStr, txs: Seq[Transaction]): Block =
    buildBlockOfTxs(refTo, txs, txs.headOption.fold(0L)(_.timestamp))

  def buildBlockOfTxs(refTo: ByteStr, txs: Seq[Transaction], timestamp: Long): Block =
    customBuildBlockOfTxs(refTo, txs, defaultSigner, 1, timestamp)

  def customBuildBlockOfTxs(
      refTo: ByteStr,
      txs: Seq[Transaction],
      signer: KeyPair,
      version: Byte,
      timestamp: Long,
      bTarget: Long = DefaultBaseTarget
  ): Block =
    Block
      .buildAndSign(
        version = version,
        timestamp = timestamp,
        reference = refTo,
        baseTarget = bTarget,
        generationSignature = correctGenerationSignature(version),
        txs = txs,
        signer = signer,
        featureVotes = Seq.empty,
        rewardVote = -1L,
        stateHash = None,
        challengedHeader = None,
        finalizationVoting = None
      )
      .explicitGet()

  def customBuildMicroBlockOfTxs(
      totalRefTo: ByteStr,
      prevTotal: Block,
      txs: Seq[Transaction],
      signer: KeyPair,
      version: Byte,
      ts: Long
  ): (Block, MicroBlockWithTotalId) = {
    val newTotalBlock = customBuildBlockOfTxs(totalRefTo, prevTotal.transactionData ++ txs, signer, version, ts)
    val nonSigned = MicroBlock
      .buildAndSign(
        version,
        generator = signer,
        transactionData = txs,
        reference = prevTotal.id(),
        totalResBlockSig = newTotalBlock.signature,
        stateHash = newTotalBlock.header.stateHash,
        finalizationVoting = None
      )
      .explicitGet()
    (newTotalBlock, new MicroBlockWithTotalId(nonSigned, newTotalBlock.id()))
  }

  def buildMicroBlockOfTxs(totalRefTo: ByteStr, prevTotal: Block, txs: Seq[Transaction], signer: KeyPair): (Block, MicroBlockWithTotalId) = {
    val newTotalBlock = buildBlockOfTxs(totalRefTo, prevTotal.transactionData ++ txs)
    val nonSigned = MicroBlock
      .buildAndSign(
        3.toByte,
        generator = signer,
        transactionData = txs,
        reference = prevTotal.id(),
        totalResBlockSig = newTotalBlock.signature,
        stateHash = newTotalBlock.header.stateHash,
        finalizationVoting = None
      )
      .explicitGet()
    (newTotalBlock, new MicroBlockWithTotalId(nonSigned, newTotalBlock.id()))
  }

  def randomSig: ByteStr = TestBlock.randomOfLength(Block.BlockIdLength)

  def chainBlocks(txs: Seq[Seq[Transaction]]): Seq[Block] = {
    def chainBlocksR(refTo: ByteStr, txs: Seq[Seq[Transaction]]): Seq[Block] = txs match {
      case (x :: xs) =>
        val block = buildBlockOfTxs(refTo, x)
        block +: chainBlocksR(block.id(), xs)
      case _ => Seq.empty
    }

    chainBlocksR(randomSig, txs)
  }

  def chainBaseAndMicro(totalRefTo: ByteStr, base: Transaction, micros: Seq[Seq[Transaction]]): (Block, Seq[MicroBlockWithTotalId]) =
    chainBaseAndMicro(totalRefTo, Seq(base), micros, defaultSigner, 3, base.timestamp)

  def chainBaseAndMicro(
      totalRefTo: ByteStr,
      base: Seq[Transaction],
      micros: Seq[Seq[Transaction]],
      signer: KeyPair,
      version: Byte,
      timestamp: Long
  ): (Block, Seq[MicroBlockWithTotalId]) = {
    val block = customBuildBlockOfTxs(totalRefTo, base, signer, version, timestamp)
    val microBlocks = micros
      .foldLeft((block, Seq.empty[MicroBlockWithTotalId])) { case ((lastTotal, allMicros), txs) =>
        val (newTotal, micro) = customBuildMicroBlockOfTxs(totalRefTo, lastTotal, txs, signer, version, timestamp)
        (newTotal, allMicros :+ micro)
      }
      ._2
    (block, microBlocks)
  }

  def spoilSignature(b: Block): Block = b.copy(signature = TestBlock.randomSignature())
}
