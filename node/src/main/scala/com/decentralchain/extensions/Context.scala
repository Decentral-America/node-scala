package com.decentralchain.extensions

import com.decentralchain.api.common.*
import com.decentralchain.common.state.ByteStr
import com.decentralchain.events.UtxEvent
import com.decentralchain.lang.ValidationError
import com.decentralchain.settings.DCCSettings
import com.decentralchain.state.Blockchain
import com.decentralchain.transaction.smart.script.trace.TracedResult
import com.decentralchain.transaction.{DiscardedBlocks, Transaction}
import com.decentralchain.utils.Time
import com.decentralchain.utx.UtxPool
import com.decentralchain.wallet.Wallet
import monix.eval.Task
import monix.reactive.Observable

trait Context {
  def settings: DCCSettings
  def blockchain: Blockchain
  def rollbackTo(blockId: ByteStr): Task[Either[ValidationError, DiscardedBlocks]]
  def time: Time
  def wallet: Wallet
  def utx: UtxPool

  def transactionsApi: CommonTransactionsApi
  def blocksApi: CommonBlocksApi
  def accountsApi: CommonAccountsApi
  def assetsApi: CommonAssetsApi
  def generatorsApi: CommonGeneratorsApi

  def broadcastTransaction(tx: Transaction): TracedResult[ValidationError, Boolean]
  def utxEvents: Observable[UtxEvent]
}
