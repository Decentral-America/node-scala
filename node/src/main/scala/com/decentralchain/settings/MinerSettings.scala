package com.decentralchain.settings

import com.decentralchain.account.PrivateKey
import com.decentralchain.mining.Miner
import pureconfig.*

import scala.concurrent.duration.FiniteDuration

case class MinerSettings(
    enable: Boolean,
    quorum: Int,
    intervalAfterLastBlockThenGenerationIsAllowed: FiniteDuration,
    noQuorumMiningDelay: FiniteDuration,
    microBlockInterval: FiniteDuration,
    minimalBlockGenerationOffset: FiniteDuration,
    maxTransactionsInMicroBlock: Int,
    minMicroBlockAge: FiniteDuration,
    privateKeys: Seq[PrivateKey],
    // In-process replacement for the external auto-commit-generators/commit-generators-hotstuff
    // GH Actions crons: when true, MinerImpl self-checks committee membership at every key-block
    // forge and self-submits a CommitToGenerationTransaction if it's about to fall out of the next
    // period's committee. Default false until a testnet soak confirms it; see
    // docs/superpowers/plans/2026-09-12-inprocess-self-commit-generation.md.
    selfCommitToGeneration: Boolean = false
) derives ConfigReader {
  require(maxTransactionsInMicroBlock <= Miner.MaxTransactionsPerMicroblock)
}
