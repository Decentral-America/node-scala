package com.decentralchain.state

import com.decentralchain.block.{Block, BlockSnapshot, MicroBlock, MicroBlockSnapshot}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.history.Domain
import com.decentralchain.mining.MiningConstraint
import com.decentralchain.network.{BlockSnapshotResponse, ExtensionBlocks, InvalidBlockStorage, PeerDatabase}
import io.decentralchain.protobuf.PBSnapshots
import com.decentralchain.settings.DCCSettings
import com.decentralchain.state.BlockchainUpdaterImpl.BlockApplyResult.Applied
import com.decentralchain.state.appender.{BlockAppender, ExtensionAppender}
import com.decentralchain.state.diffs.BlockDiffer
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.account.{AddressScheme, KeyPair}
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey}
import com.decentralchain.TestValues
import com.decentralchain.transaction.{CommitToGenerationTransaction, TxHelpers, TxVersion}
import com.decentralchain.transaction.TxValidationError.InvalidStateHash
import io.netty.channel.embedded.EmbeddedChannel
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global

import scala.collection.immutable.VectorMap

class LightNodeTest extends PropSpec with WithDomain {

  val settings: DCCSettings     = DomainPresets.TransactionStateSnapshot.copy(enableLightMode = true)
  val invalidStateHash: ByteStr = ByteStr.fill(32)(1)

  property("NODE-1148. Light node shouldn't apply block when its state hash differs from snapshot state hash") {
    val sender    = TxHelpers.signer(1)
    val recipient = TxHelpers.address(2)
    withDomain(settings.configure(_.copy(lightNodeBlockFieldsAbsenceInterval = 0)), AddrWithBalance.enoughBalances(sender)) { d =>
      val prevBlock    = d.lastBlock
      val txs          = Seq(TxHelpers.transfer(sender, recipient, amount = 10.dcc), TxHelpers.transfer(sender, recipient, amount = 100.dcc))
      val validBlock   = d.createBlock(Block.ProtoBlockVersion, txs)
      val invalidBlock = d.createBlock(Block.ProtoBlockVersion, txs, stateHash = Some(Some(invalidStateHash)))
      val txSnapshots  = getTxSnapshots(d, validBlock)

      d.appendBlockE(
        invalidBlock,
        Some(BlockSnapshot(invalidBlock.id(), txSnapshots))
      ) shouldBe Left(InvalidStateHash(Some(invalidStateHash), validBlock.header.stateHash))
      d.lastBlock shouldBe prevBlock

      d.appendBlockE(
        validBlock,
        Some(BlockSnapshot(validBlock.id(), txSnapshots))
      ) should beRight
      d.lastBlock shouldBe validBlock
    }
  }

  property("NODE-1149. Light node may apply block with invalid state hash if snapshot state hash is equal to block state hash") {
    val sender          = TxHelpers.signer(1)
    val recipient       = TxHelpers.address(2)
    val txs             = Seq(TxHelpers.transfer(sender, recipient, amount = 10.dcc), TxHelpers.transfer(sender, recipient, amount = 100.dcc))
    val invalidBlockTxs = Seq(TxHelpers.transfer(sender, recipient, amount = 20.dcc), TxHelpers.transfer(sender, recipient, amount = 200.dcc))

    withDomain(settings, AddrWithBalance.enoughBalances(sender)) { d =>
      val validBlockWithOtherTxs = d.createBlock(Block.ProtoBlockVersion, txs)

      val invalidBlock = d.createBlock(Block.ProtoBlockVersion, invalidBlockTxs, stateHash = Some(validBlockWithOtherTxs.header.stateHash))

      val txSnapshots = getTxSnapshots(d, validBlockWithOtherTxs)

      d.appendBlockE(invalidBlock, Some(BlockSnapshot(invalidBlock.id(), txSnapshots))) should beRight
      d.lastBlock shouldBe invalidBlock
    }
  }

  property("NODE-1143. Rollback returns discarded block snapshots only for light node") {
    val sender    = TxHelpers.signer(1)
    val recipient = TxHelpers.address(2)

    Seq(true -> None, false -> Some(List.empty[BlockSnapshot])).foreach { case (isLightMode, maybeExpectedSnapshots) =>
      withDomain(DomainPresets.TransactionStateSnapshot.copy(enableLightMode = isLightMode), AddrWithBalance.enoughBalances(sender)) { d =>
        val genesisSignature = d.lastBlockId

        def newBlocks(count: Int): List[BlockSnapshot] = {
          if (count == 0) {
            Nil
          } else {
            val txs =
              Seq(
                TxHelpers.transfer(sender, recipient, amount = (count + 1).dcc),
                TxHelpers.transfer(sender, recipient, amount = (count + 2).dcc)
              )
            val block       = d.createBlock(Block.ProtoBlockVersion, txs)
            val txSnapshots = getTxSnapshots(d, block).map { case (snapshot, status) => snapshot.copy(transactions = VectorMap.empty) -> status }
            d.appendBlock(block)
            BlockSnapshot(block.id(), txSnapshots) :: newBlocks(count - 1)
          }
        }

        val blockSnapshots  = newBlocks(10)
        val discardedBlocks = d.rollbackTo(genesisSignature)
        discardedBlocks.head.block.header.reference shouldBe genesisSignature
        discardedBlocks.flatMap(_.snapshot).toList shouldBe maybeExpectedSnapshots.getOrElse(blockSnapshots)
        discardedBlocks.foreach { x =>
          d.appendBlockE(x.block, x.snapshot) should beRight
        }
      }
    }
  }

  property("NODE-1165, NODE-1166. Full and light nodes should correctly switch to branch with better score") {
    val sender    = TxHelpers.signer(1)
    val recipient = TxHelpers.address(2)

    Seq(true, false).foreach { isLightMode =>
      withDomain(
        DomainPresets.TransactionStateSnapshot.copy(enableLightMode = isLightMode),
        AddrWithBalance.enoughBalances(sender, TxHelpers.defaultSigner)
      ) { d =>
        val chainSize    = 3
        val genesisId    = d.lastBlockId
        val betterBlocks = (1 to chainSize).map { idx =>
          val txs =
            Seq(TxHelpers.transfer(sender, recipient, amount = (idx + 10).dcc), TxHelpers.transfer(sender, recipient, amount = (idx + 11).dcc))
          val block       = d.createBlock(Block.ProtoBlockVersion, txs, strictTime = true)
          val txSnapshots = if (isLightMode) Some(getTxSnapshots(d, block)) else None
          d.appendBlock(block)
          block -> txSnapshots
        }
        val expectedStateHash = d.lastBlock.header.stateHash
        d.rollbackTo(genesisId)

        (1 to chainSize).foreach { idx =>
          val txs = Seq(TxHelpers.transfer(sender, recipient, amount = idx.dcc), TxHelpers.transfer(sender, recipient, (idx + 1).dcc))
          d.appendBlock(txs*)
        }
        val currentScore = d.blockchain.score

        val extensionBlocks = ExtensionBlocks(
          currentScore + 1,
          betterBlocks.map(_._1),
          betterBlocks.collect { case (b, Some(snapshots)) =>
            b.id() -> BlockSnapshotResponse(b.id(), snapshots.map { case (s, m) => PBSnapshots.toProtobuf(s, m) })
          }.toMap
        )

        val appender =
          ExtensionAppender(
            d.blockchain,
            d.utxPool,
            d.posSelector,
            TestTime(extensionBlocks.blocks.last.header.timestamp),
            InvalidBlockStorage.NoOp,
            PeerDatabase.NoOp,
            Scheduler.global
          )(
            new EmbeddedChannel(),
            _
          )

        appender(extensionBlocks).runSyncUnsafe() should beRight
        d.lastBlock.header.stateHash shouldBe expectedStateHash
        d.blockchain.height shouldBe chainSize + 1
        d.blocksApi.blocksRange(Height(2), Height(d.blockchain.height)).toListL.runSyncUnsafe().map(_._1.header) shouldBe betterBlocks.map(
          _._1.header
        )
      }
    }
  }

  property("NODE-1168. Light node should correctly apply challenging block") {
    val sender           = TxHelpers.signer(1)
    val recipient        = TxHelpers.address(2)
    val challengingMiner = TxHelpers.signer(3)

    withDomain(
      settings.configure(_.copy(lightNodeBlockFieldsAbsenceInterval = 0)),
      AddrWithBalance.enoughBalances(challengingMiner, TxHelpers.defaultSigner, sender)
    ) { d =>
      val txs              = Seq(TxHelpers.transfer(sender, recipient, amount = 1.dcc), TxHelpers.transfer(sender, recipient, amount = 2.dcc))
      val invalidBlock     = d.createBlock(Block.ProtoBlockVersion, txs, strictTime = true, stateHash = Some(Some(invalidStateHash)))
      val challengingBlock = d.createChallengingBlock(challengingMiner, invalidBlock, strictTime = true)
      val txSnapshots      = getTxSnapshots(d, challengingBlock)

      val appender = BlockAppender(
        d.blockchainUpdater,
        TestTime(challengingBlock.header.timestamp),
        d.utxPool,
        d.posSelector,
        BlockEndorser.Disabled,
        Scheduler.global
      )

      val sr = BlockSnapshotResponse(challengingBlock.id(), txSnapshots.map { case (s, m) => PBSnapshots.toProtobuf(s, m) })
      appender(challengingBlock, Some(sr)).runSyncUnsafe() shouldBe Right(
        Applied(Seq.empty, d.blockchain.score, Seq.empty)
      )
      d.lastBlock shouldBe challengingBlock
    }
  }

  // C1 (BLS crypto audit): the light-node snapshot path must not accept a CommitToGenerationTransaction
  // whose BLS proof-of-possession is invalid, or whose endorser key is not a valid curve point.
  //
  // The snapshot branch of BlockDiffer.fromBlock runs no TransactionDiffer, and TransactionDiffer is the
  // only caller of CommitToGenerationTransactionDiff -- where PoP verification and BlsPublicKey.validated
  // live. Nothing downstream compensates: the state hash is computed over the peer's own snapshot (and
  // excludes nextCommittedGenerators outright), committedGeneratorsHash accepts None unconditionally, and
  // persistence reads the key straight off the unvalidated transaction. So without the explicit
  // re-validation in BlockDiffer.validateCommitmentsOnSnapshotPath, every case below is ACCEPTED, seating
  // an attacker-chosen BLS key as a committed generator on the light node.
  private val finalitySettings: DCCSettings =
    DomainPresets.DeterministicFinality
      .copy(enableLightMode = true)
      .configure(_.copy(generationPeriodLength = 3, lightNodeBlockFieldsAbsenceInterval = 0))

  // Compressed G1 point at infinity: high (compressed) bit + infinity bit set, rest zero.
  private val pointAtInfinityKey: BlsPublicKey = {
    val bytes = new Array[Byte](BlsPublicKey.SizeInBytes)
    bytes(0) = 0xc0.toByte
    BlsPublicKey(bytes).explicitGet()
  }

  private def commitmentAttackCases(sender: KeyPair, periodStart: Height): Seq[(String, CommitToGenerationTransaction)] = {
    val honestKp = BlsKeyPair(sender.privateKey)
    val otherKp  = BlsKeyPair(TxHelpers.signer(9).privateKey)

    Seq(
      // PoP signed by a DIFFERENT key than the one being registered: the rogue-key attack proper.
      "invalid proof-of-possession (signature from another key)" ->
        CommitToGenerationTransaction
          .selfSigned(
            TxVersion.V1,
            sender,
            honestKp.publicKey,
            periodStart,
            TxHelpers.timestamp,
            TestValues.commitToGenerationFee,
            CommitToGenerationTransaction.mkPopSignature(otherKp, periodStart),
            AddressScheme.current.chainId
          )
          .explicitGet(),
      // Point at infinity passes the 48-byte sanity check in BlsPublicKey.apply but is rejected by
      // .validated. Admitting it corrupts additive key aggregation in BlsUtils.verifyAgg.
      "endorser key is the point at infinity" ->
        CommitToGenerationTransaction
          .selfSigned(
            TxVersion.V1,
            sender,
            pointAtInfinityKey,
            periodStart,
            TxHelpers.timestamp,
            TestValues.commitToGenerationFee,
            CommitToGenerationTransaction.mkPopSignature(honestKp, periodStart),
            AddressScheme.current.chainId
          )
          .explicitGet()
    )
  }

  property("C1. Light node must reject snapshot-path blocks committing a BLS key with invalid PoP or bad curve point") {
    val sender      = TxHelpers.signer(1)
    val periodStart = Height(4)

    commitmentAttackCases(sender, periodStart).foreach { case (label, maliciousTx) =>
      withClue(s"$label: ") {
        withDomain(
          finalitySettings,
          Seq(AddrWithBalance(sender.toAddress, 100_000.dcc + CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee))
        ) { d =>
          val prevBlock = d.lastBlock

          // A well-formed commitment from the same sender, used only to obtain a structurally valid
          // set of per-transaction snapshots -- i.e. exactly what a malicious peer would serve.
          val honestTx    = TxHelpers.commitToGeneration(periodStart, sender)
          val honestBlock = d.createBlock(Block.ProtoBlockVersion, Seq(honestTx))
          val txSnapshots = getTxSnapshots(d, honestBlock)

          val maliciousBlock = d.createBlock(Block.ProtoBlockVersion, Seq(maliciousTx), stateHash = Some(honestBlock.header.stateHash))

          val result = d.appendBlockE(maliciousBlock, Some(BlockSnapshot(maliciousBlock.id(), txSnapshots)))

          result should beLeft
          d.lastBlock shouldBe prevBlock
          // And the attacker's key never became a committed generator.
          d.blockchain
            .committedGenerators(d.blockchain.currentGenerationPeriod.get.next)
            .map(_._2) should not contain maliciousTx.endorserPublicKey
        }
      }
    }
  }

  property("C1. Light node still accepts a snapshot-path block with a valid commitment") {
    val sender      = TxHelpers.signer(1)
    val periodStart = Height(4)

    withDomain(
      finalitySettings,
      Seq(AddrWithBalance(sender.toAddress, 100_000.dcc + CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee))
    ) { d =>
      val tx          = TxHelpers.commitToGeneration(periodStart, sender)
      val block       = d.createBlock(Block.ProtoBlockVersion, Seq(tx))
      val txSnapshots = getTxSnapshots(d, block)

      d.appendBlockE(block, Some(BlockSnapshot(block.id(), txSnapshots))) should beRight
      d.lastBlock shouldBe block
      d.blockchain
        .committedGenerators(d.blockchain.currentGenerationPeriod.get.next)
        .map(_._2) should contain(tx.endorserPublicKey)
    }
  }

  // On the full-node path, a transaction whose diff fails inside a CHALLENGED block is elided rather
  // than fatal (see the `hasChallenge` recover in BlockDiffer's validating `apply`). If the light-node
  // snapshot check rejected such a block outright, a light node would reject a block full nodes accept
  // -- a consensus-relevant divergence that could fork or stall it. So commitments the peer declared
  // Elided are skipped by the check: they contribute no state on either node type.
  property("C1. Light node matches full-node behavior: a commitment the peer declared Elided does not reject the block") {
    val sender           = TxHelpers.signer(1)
    val challengingMiner = TxHelpers.signer(3)
    val periodStart      = Height(4)

    val badPopTx = commitmentAttackCases(sender, periodStart).head._2

    withDomain(
      finalitySettings,
      Seq(
        AddrWithBalance(sender.toAddress, 100_000.dcc + CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee),
        AddrWithBalance(challengingMiner.toAddress, 100_000.dcc),
        AddrWithBalance(TxHelpers.defaultSigner.toAddress, 100_000.dcc)
      )
    ) { d =>
      val elidedSnapshots: Seq[(StateSnapshot, TxMeta.Status)] = Seq(StateSnapshot.empty -> TxMeta.Status.Elided)

      // An explicit stateHash is required: createBlock would otherwise compute one, which runs full
      // validation and throws on the invalid PoP before the snapshot path is ever reached.
      val block = d.createBlock(Block.ProtoBlockVersion, Seq(badPopTx), strictTime = true, stateHash = Some(Some(invalidStateHash)))

      // The check must not be what rejects this block: an Elided commitment is skipped entirely.
      // (The append may still fail later on state-hash grounds -- that is a different, non-divergent
      // check -- so we assert specifically that the failure is NOT our commitment validation.)
      val result = d.appendBlockE(block, Some(BlockSnapshot(block.id(), elidedSnapshots)))
      result.left.toOption.map(_.toString).getOrElse("") should not include "Invalid commitment signature"
    }
  }

  // The microblock snapshot path (BlockDiffer.fromMicroBlockTraced) is structurally identical to the
  // key-block one and reachable by the same light-node population: MicroBlockSynchronizer requests
  // snapshots only when isLightMode, nothing stops a CommitToGenerationTransaction from being packed
  // into a microblock, and Caches seats the generator from a microblock snapshot the same way.
  // Without the shared guard, an attacker blocked at the key-block path just moves here.
  property("C1. Light node must reject microblock-snapshot-path blocks committing a BLS key with invalid PoP or bad curve point") {
    val sender      = TxHelpers.signer(1)
    val honestOther = TxHelpers.signer(4)
    val periodStart = Height(4)

    commitmentAttackCases(sender, periodStart).foreach { case (label, maliciousTx) =>
      withClue(s"microblock / $label: ") {
        withDomain(
          finalitySettings,
          Seq(
            AddrWithBalance(sender.toAddress, 100_000.dcc + CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee),
            AddrWithBalance(
              honestOther.toAddress,
              100_000.dcc + CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee
            )
          )
        ) { d =>
          d.appendBlock()

          // Structurally valid snapshots, derived from an honest commitment by a DIFFERENT sender --
          // what a malicious peer would serve alongside a microblock whose transaction actually
          // carries the forged endorser key. A different sender keeps this derivation independent of
          // the attack key (the infinity case reuses `sender`'s own BLS key).
          val honestMicro    = d.createMicroBlock()(TxHelpers.commitToGeneration(periodStart, honestOther))
          val honestSnapshot = getMicroBlockTxSnapshots(d, honestMicro)

          // The malicious microblock must reuse the honest state hash: createMicroBlock would
          // otherwise compute one itself, which runs full validation and rejects the tx before we
          // ever reach the snapshot path under test.
          val maliciousMicro = d.createMicroBlock(stateHash = honestMicro.stateHash)(maliciousTx)

          d.appendMicroBlockE(
            maliciousMicro,
            Some(MicroBlockSnapshot(maliciousMicro.totalResBlockSig, honestSnapshot))
          ) should beLeft

          d.blockchain
            .committedGenerators(d.blockchain.currentGenerationPeriod.get.next)
            .map(_._2) should not contain maliciousTx.endorserPublicKey
        }
      }
    }
  }

  property("C1. Light node still accepts a microblock-snapshot-path block with a valid commitment") {
    val sender      = TxHelpers.signer(1)
    val periodStart = Height(4)

    withDomain(
      finalitySettings,
      Seq(AddrWithBalance(sender.toAddress, 100_000.dcc + CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee))
    ) { d =>
      d.appendBlock()

      val tx        = TxHelpers.commitToGeneration(periodStart, sender)
      val micro     = d.createMicroBlock()(tx)
      val snapshots = getMicroBlockTxSnapshots(d, micro)

      d.appendMicroBlockE(micro, Some(MicroBlockSnapshot(micro.totalResBlockSig, snapshots))) should beRight
      d.blockchain
        .committedGenerators(d.blockchain.currentGenerationPeriod.get.next)
        .map(_._2) should contain(tx.endorserPublicKey)
    }
  }

  private def getMicroBlockTxSnapshots(d: Domain, micro: MicroBlock): Seq[(StateSnapshot, TxMeta.Status)] =
    BlockDiffer
      .fromMicroBlock(
        d.blockchain,
        d.blockchain.lastBlockTimestamp,
        d.blockchain.lastStateHash(Some(micro.reference)),
        micro,
        None,
        MiningConstraint.Unlimited
      )
      .explicitGet()
      .snapshot
      .transactions
      .values
      .toSeq
      .map(txInfo => txInfo.snapshot -> txInfo.status)

  private def getTxSnapshots(d: Domain, block: Block): Seq[(StateSnapshot, TxMeta.Status)] = {
    val lb                                            = d.liquidState.get.liquidBlockOf(block.header.reference).get
    val (refBlock, refSnapshot, carry, prevStateHash) = (lb.block, lb.data.snapshot, lb.data.carryFee, lb.data.liquidStateHash)

    val hs = d.posSelector.validateGenerationSignature(block).explicitGet()

    val referencedBlockchain = SnapshotBlockchain(
      d.rocksDBWriter,
      refSnapshot,
      refBlock,
      d.liquidState.get.hitSource,
      carry,
      Some(d.settings.blockchainSettings.rewardsSettings.initial),
      Some(prevStateHash)
    )

    val snapshot =
      BlockDiffer
        .fromBlock(referencedBlockchain, Some(refBlock), block, None, MiningConstraint.Unlimited, hs, None)
        .explicitGet()
        .snapshot

    snapshot.transactions.values.toSeq.map(txInfo => txInfo.snapshot -> txInfo.status)
  }
}
