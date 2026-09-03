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
            CommitToGenerationTransaction.mkPopSignature(otherKp, periodStart, sender.publicKey, AddressScheme.current.chainId, cryptoV2 = false),
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
            CommitToGenerationTransaction.mkPopSignature(honestKp, periodStart, sender.publicKey, AddressScheme.current.chainId, cryptoV2 = false),
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

  // The snapshot-path guard must NEVER key off the peer's declared transaction status. That status is
  // an attacker-controlled wire field, and TxMeta.Status.fromProtobuf maps any unrecognized enum value
  // to Elided (`case _ => Elided`), so a peer could set one protobuf field to skip validation while
  // still smuggling a rogue key through nextCommittedGenerators -- which the per-tx state hash
  // excludes by design, and which the snapshot fold merges unconditionally.
  //
  // This is the regression test for that: the peer declares Elided AND supplies a snapshot seating the
  // rogue key. The assertion is on the real security property -- the key must not become a generator.
  property("C1. Light node must not let a peer-declared Elided status bypass commitment validation") {
    val sender      = TxHelpers.signer(1)
    val periodStart = Height(4)

    commitmentAttackCases(sender, periodStart).foreach { case (label, maliciousTx) =>
      withClue(s"peer-declared Elided / $label: ") {
        withDomain(
          finalitySettings,
          Seq(AddrWithBalance(sender.toAddress, 100_000.dcc + CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee))
        ) { d =>
          // A snapshot that both claims Elided AND seats the attacker's key -- the exact shape a
          // malicious peer would send to skip validation while still smuggling the rogue key
          // (nextCommittedGenerators is excluded from the per-tx state hash, so this costs it nothing).
          val rogueSnapshot = StateSnapshot(nextCommittedGenerators = Seq(maliciousTx.sender -> maliciousTx.endorserPublicKey))
          val peerSnapshots = Seq(rogueSnapshot -> TxMeta.Status.Elided)

          val block = d.createBlock(Block.ProtoBlockVersion, Seq(maliciousTx), strictTime = true, stateHash = Some(Some(invalidStateHash)))

          // Asserted directly on BlockDiffer so the state-hash check cannot mask the result: the guard
          // itself must reject this, on commitment grounds, despite the Elided declaration.
          val hs     = d.posSelector.validateGenerationSignature(block).explicitGet()
          val result = BlockDiffer.fromBlock(
            d.blockchain,
            Some(d.lastBlock),
            block,
            Some(BlockSnapshot(block.id(), peerSnapshots)),
            MiningConstraint.Unlimited,
            hs,
            None
          )

          result should beLeft
          result.left.toOption.map(_.toString).getOrElse("") should include("commitment")
        }
      }
    }
  }

  // The combination none of the earlier tests covered: challengedHeader set on a block that is served
  // TOGETHER WITH a snapshot. `challengedHeader` is an ordinary block-header field, so a peer sets it
  // freely; and BlockDiffer's challenge-legitimacy re-derivation is gated on `snapshot.isEmpty`, so on
  // the light-node snapshot path the challenge is never verified. Any gate keyed on "is this block
  // challenged?" is therefore attacker-controlled here, exactly like the Elided status field was.
  // Validation must run regardless of the challenge declaration.
  property("C1. Light node must validate commitments even when the served block declares a challenge") {
    val sender          = TxHelpers.signer(1)
    val challengedMiner = TxHelpers.signer(2)
    val periodStart     = Height(4)

    commitmentAttackCases(sender, periodStart).foreach { case (label, maliciousTx) =>
      withClue(s"challengedHeader + snapshot / $label: ") {
        withDomain(
          finalitySettings,
          Seq(
            AddrWithBalance(sender.toAddress, 100_000.dcc + CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee),
            AddrWithBalance(challengedMiner.toAddress, 100_000.dcc),
            AddrWithBalance(TxHelpers.defaultSigner.toAddress, 100_000.dcc)
          )
        ) { d =>
          // The peer claims Succeeded -- with a challenge-based bypass it does not even need Elided,
          // because the guard is skipped before any status is consulted.
          val rogueSnapshot = StateSnapshot(nextCommittedGenerators = Seq(maliciousTx.sender -> maliciousTx.endorserPublicKey))
          val peerSnapshots = Seq(rogueSnapshot -> TxMeta.Status.Succeeded)

          val original = d.createBlock(
            Block.ProtoBlockVersion,
            Seq(maliciousTx),
            strictTime = true,
            generator = challengedMiner,
            stateHash = Some(Some(invalidStateHash))
          )
          val challengingBlock = d.createChallengingBlock(TxHelpers.defaultSigner, original, strictTime = true)
          challengingBlock.header.challengedHeader shouldBe defined

          val hs     = d.posSelector.validateGenerationSignature(challengingBlock).explicitGet()
          val result = BlockDiffer.fromBlock(
            d.blockchain,
            Some(d.lastBlock),
            challengingBlock,
            Some(BlockSnapshot(challengingBlock.id(), peerSnapshots)),
            MiningConstraint.Unlimited,
            hs,
            None
          )

          result should beLeft
          result.left.toOption.map(_.toString).getOrElse("") should include("commitment")
        }
      }
    }
  }

  // The Caches fix closes the PERSISTENCE path. This covers the liquid-state window before it:
  // SnapshotBlockchain.committedGenerators reads snapshot.nextCommittedGenerators, and the snapshot
  // fold merges an elided transaction's peer-supplied snapshot data unconditionally (only its fee is
  // skipped). Without a status filter there, an elided commitment's key is readable as a committed
  // generator for as long as the block is liquid -- and this path feeds endorsement validation and the
  // committedGeneratorsHash computation.
  property("C1. An elided commitment's generator is not visible during the liquid-state window") {
    val sender      = TxHelpers.signer(1)
    val periodStart = Height(4)

    withDomain(
      finalitySettings,
      Seq(AddrWithBalance(sender.toAddress, 100_000.dcc + CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee))
    ) { d =>
      val commitTx = TxHelpers.commitToGeneration(periodStart, sender)

      // Exactly what the snapshot fold produces for an elided commitment: the generator entry is
      // present in the merged snapshot, while the transaction itself is marked Elided.
      val elidedSnapshot = StateSnapshot(
        nextCommittedGenerators = Seq(commitTx.sender -> commitTx.endorserPublicKey)
      ).withTransaction(NewTransactionInfo.create(commitTx, TxMeta.Status.Elided, StateSnapshot.empty, d.blockchain))

      val sb = SnapshotBlockchain(d.blockchain, elidedSnapshot)

      sb.currentGenerationPeriod.map(p => sb.committedGenerators(p.next).map(_._2)) shouldBe
        Some(IndexedSeq.empty[BlsPublicKey])
    }
  }

  // Phantom entry: the guard derives its work list from block.transactionData (actual transaction
  // bodies), but SnapshotBlockchain.committedGenerators reads snapshot.nextCommittedGenerators -- a
  // separate, peer-supplied field the fold merges unconditionally. A peer can therefore send a block
  // with NO CommitToGenerationTransaction at all and attach a generator entry to some other
  // transaction's snapshot: commitments.isEmpty is true, the guard returns immediately, and nothing
  // ties the entry to a validated transaction. liveNextCommittedGenerators does not catch it either --
  // it only filters entries backed by an ELIDED commitment, and a phantom entry has no backing
  // transaction of any status.
  property("C1. A phantom nextCommittedGenerators entry with no backing transaction is not visible") {
    val sender   = TxHelpers.signer(1)
    val attacker = TxHelpers.signer(7)
    val rogueKey = BlsKeyPair(TxHelpers.signer(8).privateKey).publicKey

    withDomain(finalitySettings, Seq(AddrWithBalance(sender.toAddress, 100_000.dcc))) { d =>
      // A block whose only transaction is a plain transfer -- no commitment anywhere -- yet whose
      // snapshot smuggles a generator entry.
      val transfer = TxHelpers.transfer(sender, TxHelpers.address(2), amount = 1.dcc)

      val phantomSnapshot = StateSnapshot(
        nextCommittedGenerators = Seq(attacker.publicKey -> rogueKey)
      ).withTransaction(NewTransactionInfo.create(transfer, TxMeta.Status.Succeeded, StateSnapshot.empty, d.blockchain))

      val sb = SnapshotBlockchain(d.blockchain, phantomSnapshot)

      sb.currentGenerationPeriod.map(p => sb.committedGenerators(p.next).map(_._2)) shouldBe
        Some(IndexedSeq.empty[BlsPublicKey])
    }
  }

  // Full-node parity for the one case where it is meaningful: a GENUINELY challenged block, proven by
  // constructing a real challenging block over an invalid-state-hash original. The full node elides the
  // failing transaction and accepts the block; a light node must not be stricter. Crucially the elided
  // commitment must still not seat a generator.
  property("C1. Genuinely challenged block: commitment is elided, block accepted, and no generator is seated") {
    val sender          = TxHelpers.signer(1)
    val challengedMiner = TxHelpers.signer(2)
    val periodStart     = Height(4)

    // A VALID commitment: the challenge path only accepts a challenge when the original block fails
    // with InvalidStateHash. A bad-PoP tx fails earlier with a GenericError, which makes the challenge
    // itself invalid ("Invalid block challenge") and never reaches elision -- so a bad-PoP commitment
    // is not elidable by this route at all. What we assert here is the elision invariant that matters:
    // an elided commitment must not seat a generator.
    val commitTx = TxHelpers.commitToGeneration(periodStart, challengedMiner)

    withDomain(
      DomainPresets.DeterministicFinality.configure(_.copy(generationPeriodLength = 3, lightNodeBlockFieldsAbsenceInterval = 0)),
      Seq(
        AddrWithBalance(sender.toAddress, 100_000.dcc),
        AddrWithBalance(
          challengedMiner.toAddress,
          100_000.dcc + CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee
        )
      )
    ) { d =>
      val challengingMiner = d.wallet.generateNewAccount().get
      d.appendBlock(TxHelpers.transfer(sender, challengingMiner.toAddress, 1000.dcc))

      val originalBlock = d.createBlock(
        Block.ProtoBlockVersion,
        Seq(commitTx),
        strictTime = true,
        generator = challengedMiner,
        stateHash = Some(Some(invalidStateHash))
      )
      val challengingBlock = d.createChallengingBlock(challengingMiner, originalBlock)

      d.appendBlockE(challengingBlock) should beRight
      d.transactionsApi.transactionById(commitTx.id()).map(_.status).contains(TxMeta.Status.Elided) shouldBe true

      // The period the commitment WOULD have been seated into: Caches seats into the period following
      // the one containing the block, so capture it before appending anything further.
      val seatedInto = d.blockchain.currentGenerationPeriod.get.next

      // Append further blocks so the challenging block is persisted through Caches.append -- until it
      // leaves the liquid state, the seating code under test has not run at all.
      d.appendBlock()
      d.appendBlock()

      // The elided commitment must NOT have seated a generator (Caches must consult nti.status).
      d.blockchain.committedGenerators(seatedInto).map(_._2) should not contain commitTx.endorserPublicKey
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

  // A microblock can never legitimately contain an Elided transaction: MicroBlock has no
  // challenged-header field and the full-node microblock path hardcodes hasChallenge = false. So an
  // Elided declaration there is always bogus and must not buy the peer anything.
  property("C1. Microblock path rejects a commitment regardless of a peer-declared Elided status") {
    val sender      = TxHelpers.signer(1)
    val honestOther = TxHelpers.signer(4)
    val periodStart = Height(4)

    commitmentAttackCases(sender, periodStart).foreach { case (label, maliciousTx) =>
      withClue(s"microblock peer-declared Elided / $label: ") {
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

          val honestMicro   = d.createMicroBlock()(TxHelpers.commitToGeneration(periodStart, honestOther))
          val rogueSnapshot =
            StateSnapshot(nextCommittedGenerators = Seq(maliciousTx.sender -> maliciousTx.endorserPublicKey))

          val maliciousMicro = d.createMicroBlock(stateHash = honestMicro.stateHash)(maliciousTx)

          d.appendMicroBlockE(
            maliciousMicro,
            Some(MicroBlockSnapshot(maliciousMicro.totalResBlockSig, Seq(rogueSnapshot -> TxMeta.Status.Elided)))
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
