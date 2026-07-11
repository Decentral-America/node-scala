package com.decentralchain.api

import com.decentralchain.account.{Address, Alias, PublicKey}
import io.decentralchain.api.grpc.BalanceResponse
import com.decentralchain.blockchain.SignedBlockHeaderWithVrf
import com.decentralchain.events.WrappedEvent
import io.decentralchain.events.api.grpc.protobuf.SubscribeEvent
import com.decentralchain.lang.script.Script
import com.decentralchain.state.{AssetDescription, DataEntry, Height, TransactionId}
import com.decentralchain.transaction.Asset
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.subjects.ConcurrentSubject

class TestBlockchainApi(implicit val scheduler: Scheduler) extends BlockchainApi {
  val blockchainUpdatesUpstream = ConcurrentSubject.publish[WrappedEvent[SubscribeEvent]]

  override def mkBlockchainUpdatesStream(scheduler: Scheduler): BlockchainApi.BlockchainUpdatesStream =
    new BlockchainApi.BlockchainUpdatesStream {
      override val downstream: Observable[WrappedEvent[SubscribeEvent]] = blockchainUpdatesUpstream
      override def start(fromHeight: Height, toHeight: Height): Unit    = {}
      override def close(): Unit                                        = blockchainUpdatesUpstream.onComplete()
    }

  override def getCurrentBlockchainHeight(): Height = kill("getCurrentBlockchainHeight")

  override def getActivatedFeatures(height: Height): Map[Short, Height] = kill(s"getActivatedFeatures(height=$height)")

  override def getAccountDataEntries(address: Address): Seq[DataEntry[?]]               = kill(s"getAccountDataEntries(address=$address)")
  override def getAccountDataEntry(address: Address, key: String): Option[DataEntry[?]] = kill(s"getAccountDataEntry(address=$address, $key)")
  override def getAccountScript(address: Address): Option[(PublicKey, Script)]          = kill(s"getAccountScript(address=$address)")
  override def getBlockHeader(height: Height): Option[SignedBlockHeaderWithVrf]         = kill(s"getBlockHeader(height=$height)")

  override def getBlockHeaderRange(fromHeight: Height, toHeight: Height): List[SignedBlockHeaderWithVrf] =
    kill(s"getBlockHeaderRange(fromHeight=$fromHeight, toHeight=$toHeight)")

  override def getAssetDescription(asset: Asset.IssuedAsset): Option[AssetDescription] = kill(s"getAssetDescription(asset=$asset)")
  override def resolveAlias(alias: Alias): Option[Address]                             = kill(s"resolveAlias(alias=$alias)")
  override def getBalance(address: Address, asset: Asset): Long                        = kill(s"getBalance(address=$address, asset=$asset)")
  override def getLeaseBalance(address: Address): BalanceResponse.DccBalances          = kill(s"getLeaseBalance(address=$address)")
  override def getTransactionHeight(id: TransactionId): Option[Height]                 = kill(s"getTransactionHeight(id=$id)")

  private def kill(call: String) = throw new RuntimeException(call)
}
