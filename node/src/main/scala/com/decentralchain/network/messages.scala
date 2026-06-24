package com.decentralchain.network

import com.decentralchain.account.{KeyPair, PublicKey}
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{Block, BlockEndorsement, MicroBlock}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffQC, HotStuffRound, HotStuffVote}
import com.decentralchain.crypto
import com.decentralchain.crypto.bls.BlsSignature
import com.decentralchain.network.message.MessageSpec
import io.decentralchain.protobuf.block.EndorseBlock as PBEndorseBlock
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

// ---- HotStuff T2 fast-finality messages ----

/** A single validator's BLS vote for a (blockId, height, round) triple. Message code 39. */
final case class HotStuffVoteMessage(
    voterIndex: Int,
    blockId: BlockId,
    height: Height,
    round: HotStuffRound,
    signature: BlsSignature
) extends Message {
  override def toString: String =
    s"HotStuffVote(i=$voterIndex, h=$height, r=${round.name}, b=${blockId.trim})"
}

object HotStuffVoteMessage {
  def from(v: HotStuffVote): HotStuffVoteMessage =
    HotStuffVoteMessage(v.voterIndex, v.blockId, v.height, v.round, v.signature)

  def toVote(m: HotStuffVoteMessage): HotStuffVote =
    HotStuffVote(m.voterIndex, m.blockId, m.height, m.round, m.signature)
}

/** An aggregated BLS Quorum Certificate from ≥2/3 validators. Message code 40. */
final case class HotStuffQCMessage(
    blockId: BlockId,
    height: Height,
    round: HotStuffRound,
    signerIndices: Seq[Int],
    aggregatedSignature: BlsSignature
) extends Message {
  override def toString: String =
    s"HotStuffQC(h=$height, r=${round.name}, b=${blockId.trim}, signers=${signerIndices.size})"
}

object HotStuffQCMessage {
  def from(qc: HotStuffQC): HotStuffQCMessage =
    HotStuffQCMessage(qc.blockId, qc.height, qc.round, qc.signerIndices, qc.aggregatedSignature)

  def toQC(m: HotStuffQCMessage): HotStuffQC =
    HotStuffQC(m.blockId, m.height, m.round, m.signerIndices, m.aggregatedSignature)
}
