package com.decentralchain.ride.runner.entrypoints.settings

import com.typesafe.config.*
import com.decentralchain.api.DefaultBlockchainApi
import com.decentralchain.ride.runner.BlockchainState
import com.decentralchain.ride.runner.caches.mem.MemBlockchainDataCache
import com.decentralchain.ride.runner.entrypoints.{Heights, DCCRideRunnerCompareService}
import com.decentralchain.ride.runner.requests.DefaultRequestService
import com.decentralchain.settings.*
import com.decentralchain.settings.BlockchainSettings.given
import pureconfig.*

import scala.concurrent.duration.DurationInt

case class RideRunnerGlobalSettings(
    publicApi: DccPublicApiSettings,
    blockchain: BlockchainSettings,
    restApi: RestAPISettings,
    rideRunner: RideRunnerCommonSettings,
    rideCompareService: DCCRideRunnerCompareService.Settings
) derives ConfigReader {
  // Consider the service as unhealthy if it don't update events in more than this duration.
  // Should be more than publicApi.noDataTimeout, because it could be fixed after a restart of the blockchain updates stream.
  val unhealthyIdleTimeoutMs: Long = (publicApi.noDataTimeout + 30.seconds).toMillis

  val heightsSettings = Heights.Settings(rideRunner.onEmptyStartFrom, blockchain.functionalitySettings)

  def memBlockchainDataCache: MemBlockchainDataCache.Settings = rideRunner.memBlockchainDataCache

  val blockchainApi = DefaultBlockchainApi.Settings(
    grpcApi = DefaultBlockchainApi.GrpcApiSettings(maxConcurrentRequests = rideRunner.grpcApiMaxConcurrentRequests),
    blockchainUpdatesApi = DefaultBlockchainApi.BlockchainUpdatesApiSettings(
      noDataTimeout = publicApi.noDataTimeout,
      bufferSize = rideRunner.blockchainBlocksBufferSize
    )
  )

  val blockchainState = BlockchainState.Settings(delayBeforeForceRestartBlockchainUpdates = rideRunner.delayBeforeForceRestartBlockchainUpdates)

  val requestsService = DefaultRequestService.Settings(
    enableTraces = rideRunner.enableTraces,
    enableStateChanges = rideRunner.enableStateChanges,
    evaluateScriptComplexityLimit = rideRunner.complexityLimit,
    maxTxErrorLogSize = rideRunner.maxTxErrorLogSize.toBytes.toInt,
    parallelRideRunThreads = rideRunner.parallelRideRunThreads.getOrElse(math.min(4, Runtime.getRuntime.availableProcessors() * 2)),
    cacheSize = rideRunner.responseCache.size,
    cacheTtl = rideRunner.responseCache.ttl,
    ignoredCleanupThreshold = rideRunner.responseCache.gcThreshold
  )
}

object RideRunnerGlobalSettings {
  def fromRootConfig(config: Config): RideRunnerGlobalSettings = ConfigSource.fromConfig(config).at("dcc").loadOrThrow[RideRunnerGlobalSettings]
}
