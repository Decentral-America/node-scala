package com.decentralchain.api

import com.decentralchain.account.Address
import com.decentralchain.block.Block.protoHeaderHash
import com.decentralchain.block.serialization.BlockHeaderSerializer
import com.decentralchain.block.{Block, BlockHeader, SignedBlockHeader}
import com.decentralchain.common.state.ByteStr
import io.decentralchain.protobuf.block.PBBlocks
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json}
import io.decentralchain.protobuf.toByteStr

case class BlockMeta(
    header: BlockHeader,
    signature: ByteStr,
    headerHash: Option[ByteStr],
    height: Int,
    size: Int,
    transactionCount: Int,
    totalFeeInDcc: Long,
    reward: Option[Long],
    rewardShares: Seq[(Address, Long)],
    vrf: Option[ByteStr]
) {
  def toSignedHeader: SignedBlockHeader = SignedBlockHeader(header, signature)
  def id: ByteStr                       = headerHash.getOrElse(signature)

  val json: Coeval[JsObject] = Coeval.evalOnce {
    BlockHeaderSerializer.toJson(header, size, transactionCount, signature) ++
      Json.obj("height" -> height, "totalFee" -> totalFeeInDcc) ++
      reward.fold(Json.obj())(r =>
        Json.obj(
          "reward"       -> r,
          "rewardShares" -> Json.obj(rewardShares.map[(String, Json.JsValueWrapper)] { case (addrName, reward) =>
            addrName.toString -> reward
          }*)
        )
      ) ++
      vrf.fold(Json.obj())(v => Json.obj("VRF" -> v.toString)) ++
      headerHash.fold(Json.obj())(h => Json.obj("id" -> h.toString))
  }
}

object BlockMeta {
  def fromBlock(block: Block, height: Int, totalFee: Long, reward: Option[Long], vrf: Option[ByteStr]): BlockMeta =
    BlockMeta(
      block.header,
      block.signature,
      if (block.header.version >= Block.ProtoBlockVersion) Some(protoHeaderHash(block.header)) else None,
      height,
      block.bytes().length,
      block.transactionData.length,
      totalFee,
      reward,
      Seq.empty,
      vrf
    )

  def fromPb(pbMeta: com.decentralchain.database.protobuf.BlockMeta): Option[BlockMeta] = {
    pbMeta.header.map { pbHeader =>
      BlockMeta(
        PBBlocks.vanilla(pbHeader),
        pbMeta.signature.toByteStr,
        if (pbMeta.headerHash.isEmpty) None else Some(pbMeta.headerHash.toByteStr),
        pbMeta.height,
        pbMeta.size,
        pbMeta.transactionCount,
        pbMeta.totalFeeInDcc,
        Some(pbMeta.reward),
        Seq(),
        if (pbMeta.vrf.isEmpty) None
        else Some(pbMeta.vrf.toByteStr)
      )
    }
  }
}
