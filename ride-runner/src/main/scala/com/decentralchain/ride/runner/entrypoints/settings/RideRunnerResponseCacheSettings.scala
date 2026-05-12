package com.decentralchain.ride.runner.entrypoints.settings

import com.typesafe.config.ConfigMemorySize
import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration

case class RideRunnerResponseCacheSettings(
    size: ConfigMemorySize,
    ttl: FiniteDuration,
    gcThreshold: Int
) derives ConfigReader
