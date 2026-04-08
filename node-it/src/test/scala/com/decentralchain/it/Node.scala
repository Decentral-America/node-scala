package com.decentralchain.it

import java.net.{InetSocketAddress, URL}
import scala.concurrent.duration.FiniteDuration
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import com.decentralchain.account.{KeyPair, PublicKey, SeedKeyPair}
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.util.GlobalTimer
import com.decentralchain.settings.DCCSettings
import com.decentralchain.state.diffs.FeeValidation
import com.decentralchain.transaction.TransactionType
import com.decentralchain.wallet.Wallet
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import org.asynchttpclient.*
import org.asynchttpclient.Dsl.{config as clientConfig, *}
import org.slf4j.LoggerFactory

abstract class Node(val config: Config) extends AutoCloseable {
  lazy val log: Logger = Logger(LoggerFactory.getLogger(this.name))

  val settings: DCCSettings = DCCSettings.fromRootConfig(config)
  val client: AsyncHttpClient = asyncHttpClient(
    clientConfig()
      .setKeepAlive(false)
      .setNettyTimer(GlobalTimer.instance)
  )

  lazy val grpcChannel: ManagedChannel = ManagedChannelBuilder
    .forAddress(nodeApiEndpoint.getHost, nodeExternalPort(6870))
    .usePlaintext()
    .build()

  private val wallet = Wallet(settings.walletSettings.copy(file = None))
  wallet.generateNewAccounts(1)

  def generateKeyPair(): SeedKeyPair = wallet.synchronized {
    wallet.generateNewAccount().get
  }

  val keyPair: KeyPair     = KeyPair.fromSeed(config.getString("account-seed")).explicitGet()
  val publicKey: PublicKey = PublicKey.fromBase58String(config.getString("public-key")).explicitGet()
  val address: String      = config.getString("address")

  def nodeExternalPort(internalPort: Int): Int
  def nodeApiEndpoint: URL
  def apiKey: String

  /** An address which can be reached from other containers connected to the same network (may not match the declared address). This address is
    * inaccessible from the host.
    */
  def networkAddress: InetSocketAddress

  def networkAddressAccessibleFromHost: InetSocketAddress

  override def close(): Unit = client.close()
}

object Node {
  implicit class NodeExt(val n: Node) extends AnyVal {
    def name: String               = n.settings.networkSettings.derivedNodeName
    def publicKeyStr: String       = n.publicKey.toString
    def fee(txTypeId: Byte): Long  = FeeValidation.FeeConstants(TransactionType.fromId(txTypeId)) * FeeValidation.FeeUnit
    def blockDelay: FiniteDuration = n.settings.blockchainSettings.genesisSettings.averageBlockDelay
  }
}
