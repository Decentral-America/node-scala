package com.decentralchain.mining.microblocks

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block
import com.decentralchain.mining.{MinerDebugInfo, MiningConstraint}
import com.decentralchain.settings.MinerSettings
import com.decentralchain.state.{BlockEndorser, Blockchain, EndorsementStorage, NG}
import com.decentralchain.transaction.BlockchainUpdater
import com.decentralchain.utx.UtxPool
import io.netty.channel.group.ChannelGroup
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

trait MicroBlockMiner {
  def generateMicroBlockSequence(
      account: KeyPair,
      accumulatedBlock: Block,
      restTotalConstraint: MiningConstraint,
      lastMicroBlock: Long
  ): Task[Unit]
}

object MicroBlockMiner {
  def apply(
      setDebugState: MinerDebugInfo.State => Unit,
      allChannels: ChannelGroup,
      blockchainUpdater: BlockchainUpdater & Blockchain & NG,
      utx: UtxPool,
      endorsementStorage: EndorsementStorage,
      blockEndorser: BlockEndorser,
      settings: MinerSettings,
      minerScheduler: Scheduler,
      appenderScheduler: Scheduler,
      transactionAdded: Observable[Unit]
  ): MicroBlockMiner =
    new MicroBlockMinerImpl(
      setDebugState,
      allChannels,
      blockchainUpdater,
      utx,
      endorsementStorage,
      blockEndorser,
      settings,
      minerScheduler,
      appenderScheduler,
      transactionAdded
    )
}
