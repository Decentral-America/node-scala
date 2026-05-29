package com.decentralchain.ride.runner.entrypoints.settings

import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration

case class DccPublicApiSettings(
    restApi: String,
    grpcApi: String,
    grpcBlockchainUpdatesApi: String,
    noDataTimeout: FiniteDuration
) derives ConfigReader
