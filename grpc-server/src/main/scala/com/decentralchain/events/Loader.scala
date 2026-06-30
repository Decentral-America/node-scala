package com.decentralchain.events

import com.decentralchain.api.common.CommonBlocksApi
import com.decentralchain.api.grpc.*
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.database.{DBExt, DBResource}
import com.google.common.primitives.Ints
import io.decentralchain.events.protobuf.BlockchainUpdated as PBBlockchainUpdated
import io.decentralchain.events.protobuf.BlockchainUpdated.Append.Body
import io.decentralchain.protobuf.*
import io.decentralchain.protobuf.block.PBBlock
import com.decentralchain.state.Height
import com.decentralchain.utils.ScorexLogging
import monix.reactive.Observable
import org.rocksdb.RocksDB

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

class Loader(db: RocksDB, blocksApi: CommonBlocksApi, target: Option[(Int, ByteStr)], streamId: String) extends ScorexLogging {
  // Returns (batch, nextFromHeight) where nextFromHeight is the actual RocksDB key height
  // of the entry AFTER the last one read. This avoids re-seeking into gaps when fromHeight
  // is below the first stored entry (e.g. subscriber requests from height 1 but extension
  // was cold-started and only has entries from height 8546+).
  private def loadBatch(res: DBResource, fromHeight: Height): Try[(Seq[PBBlockchainUpdated], Height)] = Try {
    res.fullIterator.seek(Repo.keyForHeight(fromHeight))
    val buffer = ArrayBuffer[PBBlockchainUpdated]()
    var nextFromHeight = fromHeight

    while (res.fullIterator.isValid && buffer.size < 100) {
      val entryHeight = Height(Ints.fromByteArray(res.fullIterator.key()))
      if (target.exists { case (h, _) => entryHeight > Height(h) }) {
        // Reached past the target solid height — stop; live handler owns from here
        nextFromHeight = entryHeight
        res.fullIterator.next() // advance so we don't re-read on next call
      } else {
        buffer.append(Loader.parseUpdate(res.fullIterator.value(), blocksApi, entryHeight))
        nextFromHeight = entryHeight + 1
        res.fullIterator.next()
      }
    }

    for ((h, id) <- target if buffer.lastOption.exists(u => u.height == h); u <- buffer.lastOption) {
      require(
        u.id.toByteArray.sameElements(id.arr),
        s"Stored update ${Base58.encode(u.id.toByteArray)} at ${u.height} does not match target $id at $h"
      )
    }

    (buffer.toSeq, nextFromHeight)
  }

  private def streamFrom(fromHeight: Height): Observable[PBBlockchainUpdated] = db.resourceObservable.flatMap { res =>
    loadBatch(res, fromHeight) match {
      case Success((nextBatch, nextFromHeight)) =>
        if (nextBatch.isEmpty) Observable.empty[PBBlockchainUpdated]
        else Observable.fromIterable(nextBatch) ++ streamFrom(nextFromHeight)
      case Failure(exception) => Observable.raiseError(exception)
    }
  }

  def loadUpdates(fromHeight: Height): Observable[PBBlockchainUpdated] = {
    log.trace(s"[$streamId] Loading stored updates from $fromHeight up to ${target.fold("the most recent one") { case (h, id) => s"$id at $h" }}")
    streamFrom(fromHeight)
  }
}

object Loader {
  def parseUpdate(bs: Array[Byte], blocksApi: CommonBlocksApi, height: Height): PBBlockchainUpdated =
    PBBlockchainUpdated
      .parseFrom(bs)
      .update(
        _.append.update(
          _.body.modify {
            case Body.Block(value) =>
              Body.Block(value.copy(block = blocksApi.blockAtHeight(height).map { case (meta, txs) =>
                PBBlock(Some(meta.header.toPBHeader), meta.signature.toByteString, txs.map(_._2.toPB))
              }))
            case other => other
          }
        )
      )

  def loadUpdate(res: DBResource, blocksApi: CommonBlocksApi, height: Height): PBBlockchainUpdated =
    parseUpdate(res.get(Repo.keyForHeight(height)), blocksApi, height)

}
