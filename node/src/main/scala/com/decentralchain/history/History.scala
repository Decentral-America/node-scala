package com.decentralchain.history

import com.decentralchain.block.{Block, MicroBlock}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.database
import com.decentralchain.database.RDB
import io.decentralchain.protobuf.PBSnapshots
import io.decentralchain.protobuf.snapshot.TransactionStateSnapshot
import com.decentralchain.state.{Blockchain, Height, StateSnapshot}

trait History {
  def loadBlockBytes(id: ByteStr): Option[(Byte, Array[Byte])]
  def loadMicroBlock(id: ByteStr): Option[MicroBlock]
  def blockIdsAfter(candidates: Seq[ByteStr], count: Int): Seq[ByteStr]
  def loadBlockSnapshots(id: ByteStr): Option[Seq[TransactionStateSnapshot]]
  def loadMicroBlockSnapshots(id: ByteStr): Option[Seq[TransactionStateSnapshot]]
}

object History {
  private def versionedBytes(block: Block): (Byte, Array[Byte]) = block.header.version -> block.bytes()

  def apply(
      blockchain: Blockchain,
      liquidBlock: ByteStr => Option[Block],
      microBlock: ByteStr => Option[MicroBlock],
      liquidBlockSnapshot: ByteStr => Option[StateSnapshot],
      microBlockSnapshot: ByteStr => Option[StateSnapshot],
      rdb: RDB
  ): History =
    new History {
      override def loadBlockBytes(id: ByteStr): Option[(Byte, Array[Byte])] =
        liquidBlock(id)
          .orElse(blockchain.heightOf(id).flatMap { h =>
            database.loadBlock(Height(h), rdb)
          })
          .map(versionedBytes)

      override def loadMicroBlock(id: ByteStr): Option[MicroBlock] = microBlock(id)

      override def blockIdsAfter(candidates: Seq[ByteStr], count: Int): Seq[ByteStr] =
        candidates.view.flatMap(blockchain.heightOf).headOption.fold[Seq[ByteStr]](Seq.empty) { firstCommonHeight =>
          (firstCommonHeight to firstCommonHeight + count).flatMap(blockchain.blockId)
        }

      override def loadBlockSnapshots(id: ByteStr): Option[Seq[TransactionStateSnapshot]] =
        liquidBlockSnapshot(id)
          .map(_.transactions.values.toSeq.map(txInfo => PBSnapshots.toProtobuf(txInfo.snapshot, txInfo.status)))
          .orElse(blockchain.heightOf(id).map { h =>
            database.loadTxStateSnapshots(Height(h), rdb)
          })

      override def loadMicroBlockSnapshots(id: ByteStr): Option[Seq[TransactionStateSnapshot]] =
        microBlockSnapshot(id)
          .map(_.transactions.values.toSeq.map { txInfo =>
            PBSnapshots.toProtobuf(txInfo.snapshot, txInfo.status)
          })
    }
}
