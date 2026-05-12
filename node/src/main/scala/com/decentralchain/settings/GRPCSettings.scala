package com.decentralchain.settings

import pureconfig.*

final case class GRPCSettings(
    host: String,
    port: Int,
    workerThreads: Int
) derives ConfigReader
