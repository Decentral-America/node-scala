package com.decentralchain.mining

import com.decentralchain.block.Block
import com.decentralchain.consensus.PoSSelector
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.history.Domain
import com.decentralchain.settings.{WalletSettings, DCCSettings}
import com.decentralchain.state.appender.BlockAppender
import com.decentralchain.state.{BlockEndorser, Blockchain, EndorsementStorage, NG, appender}
import com.decentralchain.transaction.BlockchainUpdater
import com.decentralchain.utils.Time
import com.decentralchain.utx.UtxPoolImpl
import com.decentralchain.wallet.Wallet
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.Suite

import scala.concurrent.Await
import scala.concurrent.duration.Duration.Inf

trait WithMiner extends WithDomain { suite: Suite =>
  def withMiner(
      blockchain: Blockchain & BlockchainUpdater & NG,
      time: Time,
      settings: DCCSettings,
      verify: Boolean = true,
      timeDrift: Long = appender.MaxTimeDrift
  )(
      f: (MinerImpl, Appender) => Unit
  ): Unit = {
    val pos               = PoSSelector(blockchain, settings.synchronizationSettings.maxBaseTarget)
    val channels          = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    val wallet            = Wallet(WalletSettings(None, Some("123"), None))
    val utxPool           = new UtxPoolImpl(time, blockchain, settings.utxSettings, settings.maxTxErrorLogSize, settings.minerSettings.enable)
    val minerScheduler    = Scheduler.singleThread("miner")
    val appenderScheduler = Scheduler.singleThread("appender")
    val miner = new MinerImpl(
      channels,
      blockchain,
      settings,
      time,
      utxPool,
      BlockEndorser.Disabled,
      EndorsementStorage.Disabled,
      wallet,
      pos,
      minerScheduler,
      appenderScheduler,
      Observable(),
      timeDrift
    )
    def appendBlock(b: Block) = {
      val appendTask = BlockAppender(blockchain, time, utxPool, pos, BlockEndorser.Disabled, appenderScheduler, verify, txSignParCheck = true)(b, None)
      Await.result(appendTask.runToFuture(using appenderScheduler), Inf)
    }
    f(miner, appendBlock)
    appenderScheduler.shutdown()
    minerScheduler.shutdown()
    utxPool.close()
  }

  def withDomainAndMiner(
      settings: DCCSettings,
      balances: Seq[AddrWithBalance] = Seq(),
      verify: Boolean = true,
      timeDrift: Long = appender.MaxTimeDrift
  )(
      assert: (Domain, MinerImpl, Appender) => Unit
  ): Unit =
    withDomain(settings, balances) { d =>
      withMiner(d.blockchain, d.testTime, d.settings, verify, timeDrift)(assert(d, _, _))
    }
}
