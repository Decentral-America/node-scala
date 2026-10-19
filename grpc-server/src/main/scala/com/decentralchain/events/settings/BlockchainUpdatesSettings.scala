package com.decentralchain.events.settings

import scala.concurrent.duration.FiniteDuration
import pureconfig.*

case class BlockchainUpdatesSettings(
    grpcPort: Int,
    // application.conf default is 0.0.0.0 (all interfaces); set 127.0.0.1 to keep the API local.
    bindAddress: String,
    minKeepAlive: FiniteDuration,
    workerThreads: Int
) derives ConfigReader
