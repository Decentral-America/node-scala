package com.decentralchain.network

import com.decentralchain.account.{KeyPair, PublicKey}
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{Block, BlockEndorsement, MicroBlock}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto
import com.decentralchain.network.message.MessageSpec
import io.decentralchain.protobuf.block.{
  EndorseBlock as PBEndorseBlock,
  HotStuffVote as PBHotStuffVote,
  QuorumCertificate as PBQuorumCertificate,
  HotStuffProposal as PBHotStuffProposal,
  HotStuffPhase
}
import io.decentralchain.protobuf.snapshot.{TransactionStateSnapshot, BlockSnapshot as PBBlockSnapshot, MicroBlockSnapshot as PBMicroBlockSnapshot}
import com.decentralchain.state.{GeneratorIndex, Height}
import com.decentralchain.transaction.{Signed, Transaction}
import io.decentralchain.protobuf.{toByteString, toByteStr}
import monix.eval.Coeval

import java.net.InetSocketAddress
import java.util

sealed trait Message

case object GetPeers extends Message

case class KnownPeers(peers: Seq[InetSocketAddress]) extends Message

case class GetSignatures(signatures: Seq[ByteStr]) extends Message {
  override def toString: String = s"GetSignatures(${formatSignatures(signatures)})"
}

case class Signatures(signatures: Seq[ByteStr]) extends Message {
  override def toString: String = s"Signatures(${formatSignatures(signatures)})"
}

case class GetBlock(signature: ByteStr) extends Message

case class LocalScoreChanged(newLocalScore: BigInt) extends Message

case class RawBytes(code: Byte, data: Array[Byte]) extends Message {
  override def toString: String = s"RawBytes($code, ${data.length} bytes)"

  override def equals(obj: Any): Boolean = obj match {
    case o: RawBytes => o.code == code && util.Arrays.equals(o.data, data)
    case _           => false
  }
}

object RawBytes {
  def fromTransaction(tx: Transaction): RawBytes =
    RawBytes(PBTransactionSpec.messageCode, PBTransactionSpec.serializeData(tx))

  def fromBlock(b: Block): RawBytes =
    if (b.header.version < Block.ProtoBlockVersion) RawBytes(BlockSpec.messageCode, BlockSpec.serializeData(b))
    else RawBytes(PBBlockSpec.messageCode, PBBlockSpec.serializeData(b))

  def fromMicroBlock(mb: MicroBlockResponse): RawBytes =
    if (mb.microblock.version < Block.ProtoBlockVersion)
      RawBytes(LegacyMicroBlockResponseSpec.messageCode, LegacyMicroBlockResponseSpec.serializeData(mb))
    else RawBytes(PBMicroBlockSpec.messageCode, PBMicroBlockSpec.serializeData(mb))

  def from[T <: AnyRef](spec: MessageSpec[T], message: T): RawBytes = RawBytes(spec.messageCode, spec.serializeData(message))
}

case class BlockForged(block: Block) extends Message

case class MicroBlockRequest(totalBlockSig: ByteStr) extends Message

case class MicroBlockResponse(microblock: MicroBlock, totalBlockId: BlockId) extends Message {
  override def toString: String = microblock.stringRepr(totalBlockId)
}

object MicroBlockResponse {
  def apply(mb: MicroBlock): MicroBlockResponse = {
    require(mb.version < Block.ProtoBlockVersion)
    MicroBlockResponse(mb, mb.totalResBlockSig)
  }
}

case class MicroBlockInv(sender: PublicKey, totalBlockId: ByteStr, reference: ByteStr, signature: ByteStr) extends Message with Signed {
  override protected val signatureValid: Coeval[Boolean] =
    Coeval.evalOnce(crypto.verify(signature, sender.toAddress.bytes ++ totalBlockId.arr ++ reference.arr, sender))

  override def toString: String = s"MicroBlockInv(${totalBlockId.trim} ~> ${reference.trim})"
}

object MicroBlockInv {
  def apply(sender: KeyPair, totalBlockRef: ByteStr, prevBlockRef: ByteStr): MicroBlockInv = {
    val signature = crypto.sign(sender.privateKey, sender.toAddress.bytes ++ totalBlockRef.arr ++ prevBlockRef.arr)
    new MicroBlockInv(sender.publicKey, totalBlockRef, prevBlockRef, signature)
  }
}

case class GetSnapshot(blockId: BlockId) extends Message

case class MicroSnapshotRequest(totalBlockId: BlockId) extends Message

case class BlockSnapshotResponse(blockId: BlockId, snapshots: Seq[TransactionStateSnapshot]) extends Message {
  def toProtobuf: PBBlockSnapshot = PBBlockSnapshot(blockId.toByteString, snapshots)

  override def toString: String = s"BlockSnapshotResponse($blockId, ${snapshots.size} snapshots)"
}

object BlockSnapshotResponse {
  def fromProtobuf(snapshot: PBBlockSnapshot): BlockSnapshotResponse =
    BlockSnapshotResponse(snapshot.blockId.toByteStr, snapshot.snapshots)
}

case class MicroBlockSnapshotResponse(totalBlockId: BlockId, snapshots: Seq[TransactionStateSnapshot]) extends Message {
  def toProtobuf: PBMicroBlockSnapshot =
    PBMicroBlockSnapshot(totalBlockId.toByteString, snapshots)

  override def toString: String = s"MicroBlockSnapshotResponse($totalBlockId, ${snapshots.size} snapshots)"
}

object MicroBlockSnapshotResponse {
  def fromProtobuf(snapshot: PBMicroBlockSnapshot): MicroBlockSnapshotResponse =
    MicroBlockSnapshotResponse(snapshot.totalBlockId.toByteStr, snapshot.snapshots)
}

case class EndorseBlock(endorserIndex: Int, finalizedId: BlockId, finalizedHeight: Height, endorsedId: BlockId, signature: ByteStr) extends Message {
  def toProtobuf: PBEndorseBlock = PBEndorseBlock(
    endorserIndex,
    finalizedId.toByteString,
    finalizedHeight.toInt,
    endorsedId.toByteString,
    signature.toByteString
  )

  override def toString: String = s"EndorseBlock(i=$endorserIndex, f=$finalizedId, fh=$finalizedHeight, e=$endorsedId, s=$signature)"
}

object EndorseBlock {
  def fromProtobuf(x: PBEndorseBlock): EndorseBlock = EndorseBlock(
    x.endorserIndex,
    x.finalizedBlockId.toByteStr,
    Height(x.finalizedBlockHeight),
    x.endorsedBlockId.toByteStr,
    x.signature.toByteStr
  )

  def from(x: BlockEndorsement): EndorseBlock =
    EndorseBlock(x.endorserIndex.toInt, x.finalizedId, x.finalizedHeight, x.endorsedId, x.signature.byteStr)
}

// --- T2 HotStuff BFT fast-finality wire messages (see CONSENSUS.md) ---
// Gated behind dcc.hotstuff.enabled (default off). Carried only when the engine (later steps) is
// wired in; defining the message types here does not by itself change any consensus behaviour.

/** A committed generator's vote for `blockId` in the given `view` and `phase`.
  * `signature` is a BLS signature over the canonical encoding of (view, phase, blockId, blockHeight).
  */
case class HotStuffVote(view: Int, phase: HotStuffPhase, blockId: BlockId, blockHeight: Height, voterIndex: Int, signature: ByteStr) extends Message {
  def toProtobuf: PBHotStuffVote =
    PBHotStuffVote(view, phase, blockId.toByteString, blockHeight.toInt, voterIndex, signature.toByteString)

  override def toString: String = s"HotStuffVote(view=$view, phase=$phase, block=$blockId, h=$blockHeight, voter=$voterIndex)"
}

object HotStuffVote {
  def fromProtobuf(x: PBHotStuffVote): HotStuffVote =
    HotStuffVote(x.view, x.phase, x.blockId.toByteStr, Height(x.blockHeight), x.voterIndex, x.signature.toByteStr)
}

/** Aggregated proof that ≥ 2/3 of committed stake voted for (`view`, `phase`, `blockId`).
  * `aggregatedSignature` is the BLS aggregate of the votes from `signerIndexes`.
  */
case class QuorumCertificate(
    view: Int,
    phase: HotStuffPhase,
    blockId: BlockId,
    blockHeight: Height,
    signerIndexes: Seq[Int],
    aggregatedSignature: ByteStr
) extends Message {
  def toProtobuf: PBQuorumCertificate =
    PBQuorumCertificate(view, phase, blockId.toByteString, blockHeight.toInt, signerIndexes, aggregatedSignature.toByteString)

  override def toString: String = s"QuorumCertificate(view=$view, phase=$phase, block=$blockId, h=$blockHeight, signers=${signerIndexes.size})"
}

object QuorumCertificate {
  def fromProtobuf(x: PBQuorumCertificate): QuorumCertificate =
    QuorumCertificate(x.view, x.phase, x.blockId.toByteStr, Height(x.blockHeight), x.signerIndexes, x.aggregatedSignature.toByteStr)
}

/** A leader's HotStuff proposal: extend `blockId` at `view`, justified by `justify` (the highQC the
  * leader is extending). The justify linkage is what lets replicas check the 3-chain commit rule.
  */
case class HotStuffProposal(view: Int, blockId: BlockId, justify: Option[QuorumCertificate]) extends Message {
  def toProtobuf: PBHotStuffProposal = PBHotStuffProposal(view, blockId.toByteString, justify.map(_.toProtobuf))

  override def toString: String = s"HotStuffProposal(view=$view, block=$blockId, justify=${justify.map(_.view)})"
}

object HotStuffProposal {
  def fromProtobuf(x: PBHotStuffProposal): HotStuffProposal =
    HotStuffProposal(x.view, x.blockId.toByteStr, x.justify.map(QuorumCertificate.fromProtobuf))
}
