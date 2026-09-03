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
import io.grpc.{
  CallOptions,
  Channel,
  ClientCall,
  ClientInterceptor,
  ForwardingClientCall,
  ManagedChannel,
  ManagedChannelBuilder,
  Metadata,
  MethodDescriptor
}
import org.asynchttpclient.*
import org.asynchttpclient.Dsl.{config as clientConfig, *}
import org.slf4j.LoggerFactory

abstract class Node(val config: Config) extends AutoCloseable {
  lazy val log: Logger = Logger(LoggerFactory.getLogger(this.name))

  val settings: DCCSettings   = DCCSettings.fromRootConfig(config)
  val client: AsyncHttpClient = asyncHttpClient(
    clientConfig()
      .setKeepAlive(false)
      .setNettyTimer(GlobalTimer.instance)
  )

  // The node's gRPC server sits behind ApiKeyInterceptor, which rejects EVERY call whose X-Api-Key
  // metadata doesn't hash to rest-api.api-key-hash (UNAUTHENTICATED: "Invalid API key"). The channel
  // must therefore attach the same key the REST client uses; without this every `*GrpcSuite` aborts in
  // beforeAll. Injected once at the channel level so all stubs built on it are authenticated.
  lazy val grpcChannel: ManagedChannel = ManagedChannelBuilder
    .forAddress(nodeApiEndpoint.getHost, nodeExternalPort(6870))
    .usePlaintext()
    .intercept(new Node.ApiKeyClientInterceptor(apiKey))
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

  /** Attaches the node's API key as X-Api-Key metadata to every gRPC call so the server-side
    * ApiKeyInterceptor accepts it (mirrors how the REST client sends the key).
    */
  private final class ApiKeyClientInterceptor(apiKey: String) extends ClientInterceptor {
    private val ApiKeyHeader: Metadata.Key[String] = Metadata.Key.of("X-Api-Key", Metadata.ASCII_STRING_MARSHALLER)

    override def interceptCall[ReqT, RespT](
        method: MethodDescriptor[ReqT, RespT],
        callOptions: CallOptions,
        next: Channel
    ): ClientCall[ReqT, RespT] =
      new ForwardingClientCall.SimpleForwardingClientCall[ReqT, RespT](next.newCall(method, callOptions)) {
        override def start(responseListener: ClientCall.Listener[RespT], headers: Metadata): Unit = {
          headers.put(ApiKeyHeader, apiKey)
          super.start(responseListener, headers)
        }
      }
  }

  implicit class NodeExt(val n: Node) extends AnyVal {
    def name: String               = n.settings.networkSettings.derivedNodeName
    def publicKeyStr: String       = n.publicKey.toString
    def fee(txTypeId: Byte): Long  = FeeValidation.FeeConstants(TransactionType(txTypeId)) * FeeValidation.FeeUnit
    def blockDelay: FiniteDuration = n.settings.blockchainSettings.genesisSettings.averageBlockDelay
  }
}
