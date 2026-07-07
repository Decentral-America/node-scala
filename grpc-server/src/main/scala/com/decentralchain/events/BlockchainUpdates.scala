package com.decentralchain.events

import com.decentralchain.block.{Block, MicroBlock}
import com.decentralchain.common.state.ByteStr
import io.decentralchain.events.api.grpc.protobuf.BlockchainUpdatesApiGrpc
import com.decentralchain.events.settings.BlockchainUpdatesSettings
import com.decentralchain.extensions.{Context, Extension}
import com.decentralchain.state.{Blockchain, Height, StateSnapshot}
import com.decentralchain.utils.{Schedulers, ScorexLogging}
import io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.grpc.{Metadata, Server, ServerStreamTracer, Status}
import monix.execution.schedulers.SchedulerService
import monix.execution.{ExecutionModel, Scheduler, UncaughtExceptionReporter}
import org.rocksdb.RocksDB
import pureconfig.ConfigSource

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Try

class BlockchainUpdates(private val context: Context) extends Extension with ScorexLogging with BlockchainUpdateTriggers {
  private val settings = ConfigSource.fromConfig(context.settings.config).at("dcc.blockchain-updates").loadOrThrow[BlockchainUpdatesSettings]
  private implicit val scheduler: SchedulerService = Schedulers.fixedPool(
    settings.workerThreads,
    "blockchain-updates",
    UncaughtExceptionReporter(err => log.error("Uncaught exception in BlockchainUpdates scheduler", err)),
    ExecutionModel.Default,
    rejectedExecutionHandler = new org.apache.pekko.dispatch.SaneRejectedExecutionHandler
  )
  private val rdb  = RocksDB.open(context.settings.directory + "/blockchain-updates")
  private val repo = new Repo(rdb, context.blocksApi)

  private val grpcServer: Server = NettyServerBuilder
    .forAddress(new InetSocketAddress("0.0.0.0", settings.grpcPort))
    .permitKeepAliveTime(settings.minKeepAlive.toNanos, TimeUnit.NANOSECONDS)
    .addStreamTracerFactory((fullMethodName: String, headers: Metadata) =>
      new ServerStreamTracer {
        private var callInfo = Option.empty[ServerStreamTracer.ServerCallInfo[?, ?]]
        private def callId   = callInfo.fold("???")(ci => Integer.toHexString(System.identityHashCode(ci)))

        override def serverCallStarted(callInfo: ServerStreamTracer.ServerCallInfo[?, ?]): Unit = {
          this.callInfo = Some(callInfo)
          log.trace(s"[$callId] gRPC call started: $fullMethodName, headers: $headers")
        }

        override def streamClosed(status: Status): Unit =
          log.trace(s"[$callId] gRPC call closed with status: $status")
      }
    )
    .addService(BlockchainUpdatesApiGrpc.bindService(repo, scheduler))
    .addService(ProtoReflectionServiceV1.newInstance())
    .build()

  override def start(): Unit = {
    log.info(s"BlockchainUpdates extension starting with settings $settings")

    val nodeHeight      = context.blockchain.height
    val extensionHeight = repo.height

    if (extensionHeight < nodeHeight) {
      // The extension's persisted height can fall behind the main chain's
      // (e.g. an ungraceful shutdown that skipped its last flush, a disk
      // issue isolated to this RocksDB instance, or a fresh/transplanted
      // main chain state with no matching extension history at all). There
      // is no supported way to backfill the gap after the fact -- the
      // trigger hooks that populate this DB only fire as blocks are
      // processed live. Previously this threw and crash-looped the entire
      // node over a downstream indexer being behind. Instead, reset and
      // resume tracking from the next live block; consumers of this
      // extension (e.g. blockchain-postgres-sync, websocket-api) will see
      // a gap for [extensionHeight, nodeHeight] and must backfill from
      // another source if they need that range.
      log.warn(
        s"BlockchainUpdates height $extensionHeight is lower than node height $nodeHeight -- " +
          "resetting and resuming from the next block. Historical updates for the gap are unavailable."
      )
      repo.resetToEmpty()
    } else {
      if (extensionHeight > nodeHeight) {
        log.info(s"Rolling back from $extensionHeight to node height $nodeHeight")
        repo.rollbackData(Height(nodeHeight))
      }

      val lastUpdateId = Try(ByteStr(repo.getBlockUpdate(Height(nodeHeight)).getUpdate.id.toByteArray)).toOption
      val lastBlockId  = context.blockchain.blockHeader(nodeHeight).map(_.id())

      if (lastUpdateId != lastBlockId)
        throw new IllegalStateException(s"Last update ID $lastUpdateId does not match last block ID $lastBlockId at height $nodeHeight")

      log.info(s"BlockchainUpdates startup check successful at height $nodeHeight")
    }

    grpcServer.start()
    log.info(s"BlockchainUpdates extension started gRPC API on port ${settings.grpcPort}")
  }

  override def shutdown(): Future[Unit] =
    Future {
      grpcServer.shutdown()
      grpcServer.awaitTermination(10, TimeUnit.SECONDS)

      scheduler.shutdown()
      scheduler.awaitTermination(10 seconds)
      repo.shutdown()
      rdb.close()
    }(using Scheduler.global)

  override def onProcessBlock(
      block: Block,
      snapshot: StateSnapshot,
      reward: Option[Long],
      hitSource: ByteStr,
      blockchainBeforeWithReward: Blockchain
  ): Unit = repo.onProcessBlock(block, snapshot, reward, hitSource, blockchainBeforeWithReward)

  override def onProcessMicroBlock(
      microBlock: MicroBlock,
      snapshot: StateSnapshot,
      blockchainBeforeWithReward: Blockchain,
      totalBlockId: ByteStr,
      totalTransactionsRoot: ByteStr
  ): Unit = repo.onProcessMicroBlock(microBlock, snapshot, blockchainBeforeWithReward, totalBlockId, totalTransactionsRoot)

  override def onRollback(blockchainBefore: Blockchain, toBlockId: ByteStr, toHeight: Int): Unit =
    repo.onRollback(blockchainBefore, toBlockId, toHeight)

  override def onMicroBlockRollback(blockchainBefore: Blockchain, toBlockId: ByteStr): Unit =
    repo.onMicroBlockRollback(blockchainBefore, toBlockId)
}
