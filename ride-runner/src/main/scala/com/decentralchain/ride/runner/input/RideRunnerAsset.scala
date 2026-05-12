package com.decentralchain.ride.runner.input

import com.decentralchain.account.PublicKey
import com.decentralchain.account.PublicKeys.EmptyPublicKey
import com.decentralchain.lang.script.Script
import com.decentralchain.ride.runner.input.RideRunnerInputParser.given
import pureconfig.ConfigReader

import java.nio.charset.StandardCharsets

case class RideRunnerAsset(
    issuerPublicKey: PublicKey = EmptyPublicKey,
    name: StringOrBytesAsByteArray = RideRunnerAsset.DefaultName,
    description: StringOrBytesAsByteArray = RideRunnerAsset.DefaultDescription,
    decimals: Int = 8,
    reissuable: Boolean = false,
    quantity: Long = 9007199254740991L, // In JS: MAX_SAFE_INTEGER
    script: Option[Script] = None,
    minSponsoredAssetFee: Long = 0L
) derives ConfigReader

object RideRunnerAsset {
  val DefaultName        = StringOrBytesAsByteArray("name".getBytes(StandardCharsets.UTF_8))
  val DefaultDescription = StringOrBytesAsByteArray("description".getBytes(StandardCharsets.UTF_8))
}
