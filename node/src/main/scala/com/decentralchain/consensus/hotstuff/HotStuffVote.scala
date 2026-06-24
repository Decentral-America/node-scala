package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey, BlsSignature}
import com.decentralchain.state.Height
import com.google.common.primitives.{Bytes, Ints}

import java.nio.charset.StandardCharsets

final case class HotStuffVote(
    voterIndex: Int,
    blockId: BlockId,
    height: Height,
    round: HotStuffRound,
    signature: BlsSignature
) {
  def verifySignature(blsPublicKey: BlsPublicKey): Either[String, Unit] =
    signature.verifyBasic(HotStuffVote.signingMessage(blockId, height, round), blsPublicKey)
}

object HotStuffVote {
  // Protocol prefix distinguishes T2 votes from T0 endorsements at the byte level.
  private val VotePrefix: Array[Byte] = "DCCHOTSTUFF\x00".getBytes(StandardCharsets.UTF_8)

  def signingMessage(blockId: BlockId, height: Height, round: HotStuffRound): Array[Byte] =
    Bytes.concat(VotePrefix, blockId.arr, Ints.toByteArray(height.toInt), Array(round.code))

  def sign(voterIndex: Int, blockId: BlockId, height: Height, round: HotStuffRound, kp: BlsKeyPair): HotStuffVote =
    HotStuffVote(voterIndex, blockId, height, round, kp.sign(signingMessage(blockId, height, round)))
}
