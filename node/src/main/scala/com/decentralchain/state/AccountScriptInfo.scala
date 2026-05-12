package com.decentralchain.state

import com.decentralchain.account.PublicKey
import com.decentralchain.lang.script.Script

case class AccountScriptInfo(
    publicKey: PublicKey,
    script: Script,
    verifierComplexity: Long,
    complexitiesByEstimator: Map[Int, Map[String, Long]] = Map.empty
)
