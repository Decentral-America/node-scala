package com.decentralchain.ride.runner.input

import com.decentralchain.account.PublicKey
import com.decentralchain.account.PublicKeys.EmptyPublicKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.ride.runner.input.RideRunnerInputParser.given
import pureconfig.ConfigReader

case class RideRunnerBlock(
    timestamp: Long = System.currentTimeMillis(),
    baseTarget: Long = 130,
    generationSignature: ByteStr = ByteStr(new Array[Byte](64)),
    generatorPublicKey: PublicKey = EmptyPublicKey,
    VRF: Option[ByteStr] = None,
    blockReward: Long = 600_000_000L // 6 DCC
) derives ConfigReader
