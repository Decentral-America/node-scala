package com.decentralchain.state.appender

import com.decentralchain.account.{Address, KeyPair}
import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.DigestLength
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.finalization.BaseFinalizationSpec
import com.decentralchain.history.Domain
import com.decentralchain.lang.ValidationError
import com.decentralchain.mining.MiningConstraint
import com.decentralchain.network.{BlockSnapshotResponse, ExtensionBlocks, InvalidBlockStorage, PeerDatabase}
import com.decentralchain.state.BlockchainUpdaterImpl.BlockApplyResult
import com.decentralchain.state.diffs.BlockDiffer
import com.decentralchain.state.{BlockEndorser, GeneratorIndex, Height, SnapshotBlockchain}
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.test.{NumericExt, TestTime}
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.{Transaction, TxHelpers}
import io.decentralchain.protobuf.PBSnapshots
import io.netty.channel.embedded.EmbeddedChannel
import monix.execution.Scheduler.Implicits.global

/** ECO-413: every path that appends a key block validates it against the state of the block it references, so the
  * outcome does not depend on how the block was delivered (broadcast or extension) or on which liquid state the
  * receiving node happens to hold (microblocks the block discards, or a competing liquid block it replaces).
  */
class ExtensionAndChallengeReferenceViewSpec extends BaseFinalizationSpec {
  private val g1         = TxHelpers.signer(0)
  private val g2         = TxHelpers.signer(1)
  private val g3         = TxHelpers.signer(2)
  private val challenger = TxHelpers.signer(3)

  private val invalidStateHash = ByteStr.fill(DigestLength)(1)

  private val settings = DomainPresets.DeterministicFinality
    .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
    .configure(_.copy(generationPeriodLength = 2, lightNodeBlockFieldsAbsenceInterval = 0))

  private val balances = Seq(
    AddrWithBalance(g1.toAddress, 5000.dcc),
    AddrWithBalance(g2.toAddress, 4000.dcc),
    AddrWithBalance(g3.toAddress, 3000.dcc),
    AddrWithBalance(challenger.toAddress, 3000.dcc)
  )

  private val baseTs = TxHelpers.timestamp

  private def viaBroadcast(d: Domain, block: Block, snapshot: Option[BlockSnapshotResponse] = None): Either[ValidationError, BlockApplyResult] =
    BlockAppender(d.blockchain, TestTime(block.header.timestamp), d.utxPool, d.posSelector, BlockEndorser.Disabled, global)(block, snapshot)
      .runSyncUnsafe()

  private def viaExtension(d: Domain, blocks: Seq[Block]): Either[ValidationError, Option[BigInt]] =
    ExtensionAppender(
      d.blockchain,
      d.utxPool,
      d.posSelector,
      TestTime(blocks.map(_.header.timestamp).max),
      InvalidBlockStorage.NoOp,
      PeerDatabase.NoOp,
      global
    )(new EmbeddedChannel(), ExtensionBlocks(d.blockchain.score + blocks.map(_.blockScore()).sum, blocks, Map.empty)).runSyncUnsafe()

  private def liquidGeneratorSet(d: Domain): Seq[Address] =
    d.liquidState.toSeq.flatMap(_.finalizationState.generatorSet.map(_.address))

  private def keyBlock(d: Domain, generator: KeyPair, txs: Seq[Transaction] = Nil, ref: Option[ByteStr] = None): Block =
    d.createBlock(Block.ProtoBlockVersion, txs, ref = ref.orElse(d.blockchainUpdater.lastBlockId), generator = generator, strictTime = true)

  private def commits(period: Int, tsOffset: Int, generators: KeyPair*): Seq[Transaction] =
    generators.zipWithIndex.map { case (g, i) => TxHelpers.commitToGeneration(Height(period), g, timestamp = baseTs + tsOffset + i) }

  /** Heights 2 and 3 commit g1 and g2 to the periods starting at 3 and 5. Block 4 is liquid with two microblocks: the
    * first is a transfer; `appendDiscardedMicro` adds the second, which commits g3 to the period starting at 5.
    * Returns the id of the first microblock.
    */
  private def chainUpToLiquidBlock4(d: Domain): ByteStr = {
    d.appender.appendBlockWithoutFallback(keyBlock(d, g1, commits(3, 0, g1, g2))) should beRight
    d.appender.appendBlockWithoutFallback(keyBlock(d, g1, commits(5, 10, g1, g2))) should beRight
    d.appender.appendBlockWithoutFallback(keyBlock(d, g1)) should beRight
    d.appendMicroBlock(d.createMicroBlock(signer = Some(g1))(TxHelpers.transfer(g1, g2.toAddress, timestamp = baseTs + 20)))
  }

  private def appendDiscardedMicro(d: Domain): Unit = {
    d.appendMicroBlockE(d.createMicroBlock(signer = Some(g1))(TxHelpers.commitToGeneration(Height(5), g3, timestamp = baseTs + 21))) should beRight
    withClue("the live tip counts the committer from the second microblock: ") {
      d.blockchain.committedGenerators(d.blockchain.generationPeriodOf(Height(5)).value).map(_._1) should contain(g3.toAddress)
    }
  }

  "a key block that references an earlier microblock" - {
    def run(viaExt: Boolean): (Seq[Address], Either[ValidationError, Unit]) = {
      var out = Option.empty[(Seq[Address], Either[ValidationError, Unit])]
      withDomain(settings, balances) { d =>
        val micro1Id = chainUpToLiquidBlock4(d)
        appendDiscardedMicro(d)

        val block5 = keyBlock(d, g1, ref = Some(micro1Id))
        val res    = if (viaExt) viaExtension(d, Seq(block5)).map(_ => ()) else viaBroadcast(d, block5).map(_ => ())
        out = Some((liquidGeneratorSet(d), res))
      }
      out.value
    }

    "gets the same generator set via extension as via broadcast" in {
      val broadcast = run(viaExt = false)
      val extension = run(viaExt = true)
      broadcast._2 should beRight
      broadcast._1 should contain theSameElementsAs Seq(g1.toAddress, g2.toAddress)
      extension shouldBe broadcast
    }
  }

  "a challenging block" - {
    "delivered via extension, referencing an earlier microblock, is validated against that microblock" in {
      def run(receivedSecondMicro: Boolean): (Seq[Address], Either[ValidationError, Unit]) = {
        var out = Option.empty[(Seq[Address], Either[ValidationError, Unit])]
        withDomain(settings, balances) { d =>
          val micro1Id = chainUpToLiquidBlock4(d)

          val original =
            d.createBlock(
              Block.ProtoBlockVersion,
              Nil,
              ref = Some(micro1Id),
              generator = g1,
              strictTime = true,
              stateHash = Some(Some(invalidStateHash))
            )
          val challenging = d.createChallengingBlock(challenger, original, strictTime = true, ref = Some(micro1Id))

          val res =
            if (receivedSecondMicro) {
              appendDiscardedMicro(d)
              viaExtension(d, Seq(challenging)).map(_ => ())
            } else viaBroadcast(d, challenging).map(_ => ())

          d.lastBlockId shouldBe challenging.id()
          out = Some((liquidGeneratorSet(d), res))
        }
        out.value
      }

      val neverSawSecondMicro = run(receivedSecondMicro = false)
      val sawSecondMicro      = run(receivedSecondMicro = true)
      neverSawSecondMicro._2 should beRight
      neverSawSecondMicro._1 should contain theSameElementsAs Seq(g1.toAddress, g2.toAddress)
      sawSecondMicro shouldBe neverSawSecondMicro
    }

    // The challenge replaces a competing liquid block at the same height. That block carries a conflicting
    // endorsement from g2, the generator of the challenged block; it must not affect the challenge, which does not
    // build on it.
    "delivered via broadcast, replacing a competing liquid block, is validated against its parent" in {
      def run(competingLiquidBlock: Boolean): (Seq[Address], Either[ValidationError, Unit]) = {
        var out = Option.empty[(Seq[Address], Either[ValidationError, Unit])]
        withDomain(settings, balances) { d =>
          val block2 = keyBlock(d, g1, commits(3, 0, g1, g2))
          d.appender.appendBlockWithoutFallback(block2) should beRight

          val original    = d.createBlock(Block.ProtoBlockVersion, Nil, generator = g2, strictTime = true, stateHash = Some(Some(invalidStateHash)))
          val challenging = d.createChallengingBlock(challenger, original, strictTime = true)

          if (competingLiquidBlock) {
            val competing = d.createBlock(
              Block.ProtoBlockVersion,
              Nil,
              generator = g1,
              strictTime = true,
              // Later than the challenging block, so the challenge is the better competitor.
              timestamp = Some(challenging.header.timestamp.max(d.nextBlockTime(g1)) + 1),
              finalizationVoting = Some(mkFinalizationVoting().withConflict(g2, GeneratorIndex(1), block2.id()))
            )
            viaBroadcast(d, competing) should beRight
            d.blockchain.height shouldBe 3
          }

          val res = viaBroadcast(d, challenging).map(_ => ())
          if (res.isRight) {
            d.lastBlockId shouldBe challenging.id()
            d.blockchain.height shouldBe 3
          }
          out = Some((liquidGeneratorSet(d), res))
        }
        out.value
      }

      val onParent    = run(competingLiquidBlock = false)
      val onCompeting = run(competingLiquidBlock = true)
      onParent._2 should beRight
      onParent._1 should contain theSameElementsAs Seq(g1.toAddress, g2.toAddress)
      onCompeting shouldBe onParent
    }

    // Light node: the transaction snapshots served with the challenging block are materialised at the challenging
    // block's height. When the challenge replaces a competing liquid block, the live height is already that height,
    // so it must not be used as the parent's.
    "delivered with snapshots, replacing a competing liquid block, records its state at its own height" in {
      val lightSettings = DomainPresets.TransactionStateSnapshot
        .copy(enableLightMode = true)
        .configure(_.copy(lightNodeBlockFieldsAbsenceInterval = 0))
      val sender     = TxHelpers.signer(4)
      val competitor = TxHelpers.signer(5)

      def run(competingLiquidBlock: Boolean): (Height, Either[ValidationError, Height]) = {
        var out = Option.empty[(Height, Either[ValidationError, Height])]
        withDomain(lightSettings, AddrWithBalance.enoughBalances(challenger, competitor, sender, TxHelpers.defaultSigner)) { d =>
          val issue          = TxHelpers.issue(sender)
          val original       = d.createBlock(Block.ProtoBlockVersion, Seq(issue), strictTime = true, stateHash = Some(Some(invalidStateHash)))
          val challenging    = d.createChallengingBlock(challenger, original, strictTime = true)
          val snapshot       = challengingBlockSnapshot(d, challenging)
          val expectedHeight = Height(d.blockchain.height + 1)

          if (competingLiquidBlock) {
            val competing = d.createBlock(
              Block.ProtoBlockVersion,
              Nil,
              generator = competitor,
              strictTime = true,
              timestamp = Some(challenging.header.timestamp.max(d.nextBlockTime(competitor)) + 1)
            )
            d.appendBlockE(competing) should beRight
            d.blockchain.height shouldBe expectedHeight.toInt
          }

          val res = viaBroadcast(d, challenging, Some(snapshot)).map { _ =>
            d.lastBlockId shouldBe challenging.id()
            d.blockchain.height shouldBe expectedHeight.toInt
            d.blockchain.assetDescription(IssuedAsset(issue.id())).value.lastUpdatedAt
          }
          out = Some((expectedHeight, res))
        }
        out.value
      }

      val (expected, onParent) = run(competingLiquidBlock = false)
      withClue("on its parent: ")(onParent shouldBe Right(expected))
      val (_, onCompeting) = run(competingLiquidBlock = true)
      withClue("replacing a competing liquid block: ")(onCompeting shouldBe Right(expected))
    }
  }

  // Blocks of an existing chain, replayed by a syncing node, always reference the node's own tip and arrive without
  // microblocks, so the referenced state and the live tip coincide. Replay must reproduce the producer's chain and
  // finalization data, including a key block that discarded a microblock on the producer.
  "a chain replayed via extension reproduces the producer's blocks and generator sets" in withDomain(settings, balances) { d =>
    val genesisId = d.lastBlockId
    val micro1Id  = chainUpToLiquidBlock4(d)
    appendDiscardedMicro(d)
    d.appender.appendBlockWithoutFallback(keyBlock(d, g1, ref = Some(micro1Id))) should beRight // 5, discards the second micro
    d.appender.appendBlockWithoutFallback(keyBlock(d, g2)) should beRight                       // 6
    d.appender.appendBlockWithoutFallback(keyBlock(d, g1)) should beRight                       // 7

    val tip        = d.blockchain.height
    def observed() = (2 to tip).map { h =>
      (h, d.blockchain.blockId(h), d.generatorsApi.generators(Height(h)), d.blockchain.finalizedHeightAt(Height(h)))
    }

    val produced = observed()
    withClue("period starting at 5 has only the persisted commitments: ") {
      produced.collectFirst { case (5, _, generators, _) => generators.map(_.address) }.value should contain theSameElementsAs Seq(
        g1.toAddress,
        g2.toAddress
      )
    }
    val producedLiquidSet = liquidGeneratorSet(d)
    val blocks            = d.blocksApi.blocksRange(Height(2), Height(tip)).toListL.runSyncUnsafe().map { case (meta, txs) =>
      Block(meta.header, meta.signature, txs.map(_._2))
    }
    blocks.map(_.id()) shouldBe produced.map(_._2.value)

    d.rollbackTo(genesisId)
    d.blockchain.height shouldBe 1

    viaExtension(d, blocks) should beRight
    observed() shouldBe produced
    liquidGeneratorSet(d) shouldBe producedLiquidSet
  }

  /** The transaction snapshots a peer serves for `block`, computed against the state it references. */
  private def challengingBlockSnapshot(d: Domain, block: Block): BlockSnapshotResponse = {
    val lb         = d.liquidState.value.liquidBlockOf(block.header.reference).value
    val referenced = SnapshotBlockchain(
      d.rocksDBWriter,
      lb.data.snapshot,
      lb.block,
      d.liquidState.value.hitSource,
      lb.data.carryFee,
      Some(d.settings.blockchainSettings.rewardsSettings.initial),
      Some(lb.data.liquidStateHash)
    )
    val hitSource = d.posSelector.validateGenerationSignature(block).value
    val snapshot  = BlockDiffer.fromBlock(referenced, Some(lb.block), block, None, MiningConstraint.Unlimited, hitSource, None).value.snapshot
    BlockSnapshotResponse(block.id(), snapshot.transactions.values.toSeq.map(info => PBSnapshots.toProtobuf(info.snapshot, info.status)))
  }
}
