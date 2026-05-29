package com.decentralchain.api.grpc
import io.decentralchain.api.grpc.*

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.decentralchain.common.utils.Base58
import com.decentralchain.extensions.{Extension, Context as ExtensionContext}
import com.decentralchain.settings.GRPCSettings
import com.decentralchain.utils.ScorexLogging
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import monix.execution.Scheduler
import pureconfig.ConfigSource

import java.net.InetSocketAddress
import java.util.concurrent.Executors
import scala.concurrent.Future

class GRPCServerExtension(context: ExtensionContext) extends Extension with ScorexLogging {
  private val settings = ConfigSource.fromConfig(context.settings.config).at("dcc.grpc").loadOrThrow[GRPCSettings]
  private val executor =
    Executors.newFixedThreadPool(settings.workerThreads, new ThreadFactoryBuilder().setDaemon(true).setNameFormat("grpc-server-worker-%d").build())
  private implicit val apiScheduler: Scheduler = Scheduler(executor)
  private val bindAddress                      = new InetSocketAddress(settings.host, settings.port)

  private val apiKeyHash: Array[Byte] = {
    val hash = context.settings.restAPISettings.apiKeyHash
    Base58.tryDecode(hash).getOrElse(Array.emptyByteArray)
  }

  private val server: Server = {
    val builder = NettyServerBuilder
      .forAddress(bindAddress)
      .executor(executor)
      .intercept(new ApiKeyInterceptor(apiKeyHash))
      .addService(TransactionsApiGrpc.bindService(new TransactionsApiGrpcImpl(context.blockchain, context.transactionsApi), apiScheduler))
      .addService(BlocksApiGrpc.bindService(new BlocksApiGrpcImpl(context.blocksApi), apiScheduler))
      .addService(AccountsApiGrpc.bindService(new AccountsApiGrpcImpl(context.accountsApi), apiScheduler))
      .addService(AssetsApiGrpc.bindService(new AssetsApiGrpcImpl(context.assetsApi, context.accountsApi), apiScheduler))
      .addService(BlockchainApiGrpc.bindService(new BlockchainApiGrpcImpl(context.blockchain, context.settings.featuresSettings), apiScheduler))
    builder.build()
  }

  override def start(): Unit = {
    server.start()
    log.info(s"gRPC API was bound to $bindAddress")
  }

  override def shutdown(): Future[Unit] = {
    log.debug("Shutting down gRPC server")
    server.shutdown()
    Future(server.awaitTermination())(using apiScheduler)
  }
}
