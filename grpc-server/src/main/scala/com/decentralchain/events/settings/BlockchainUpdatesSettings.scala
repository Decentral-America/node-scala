package com.decentralchain.events.settings

import scala.concurrent.duration.FiniteDuration
import pureconfig.*

case class BlockchainUpdatesSettings(
    grpcPort: Int,
    minKeepAlive: FiniteDuration,
    workerThreads: Int
) derives ConfigReader
