package com.decentralchain.ride.runner.input

import com.decentralchain.account.PublicKey
import com.decentralchain.account.PublicKeys.EmptyPublicKey
import com.decentralchain.lang.script.Script

case class RideRunnerScriptInfo(
    publicKey: PublicKey = EmptyPublicKey,
    script: Script
)
