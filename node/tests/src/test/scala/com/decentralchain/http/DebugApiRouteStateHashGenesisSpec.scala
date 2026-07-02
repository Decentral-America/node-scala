package com.decentralchain.http
import com.typesafe.config.ConfigObject
import com.decentralchain.*
import com.decentralchain.account.KeyPair
import com.decentralchain.api.http.{DebugApiRoute, RouteTimeout}
import com.decentralchain.block.Block
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.mining.TestMiner
import com.decentralchain.network.PeerDatabase
import com.decentralchain.settings.DCCSettings
import com.decentralchain.state.Blockchain
import com.decentralchain.test.*
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.utils.SharedSchedulerMixin
import monix.eval.Task
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.OptionValues
import play.api.libs.json.{JsObject, Json}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

class DebugApiRouteStateHashGenesisSpec
    extends RouteSpec("/debug")
    with RestAPISettingsHelper
    with TestWallet
    with NTPTime
    with SharedDomain
    with OptionValues
    with SharedSchedulerMixin {

  override def settings: DCCSettings = DomainPresets.DeterministicFinality
    .copy(
      dbSettings = DomainPresets.DeterministicFinality.dbSettings.copy(storeStateHashes = true),
      restAPISettings = restAPISettings
    )

  private val configObject: ConfigObject = settings.config.root()

  private val richAccount = TxHelpers.signer(905)

  override def genesisBalances: Seq[AddrWithBalance] = Seq(AddrWithBalance(richAccount.toAddress, 50_000.dcc))

  val block: Block = TestBlock.create(Nil).block

  val debugApiRoute: DebugApiRoute =
    DebugApiRoute(
      settings,
      ntpTime,
      domain.blockchain,
      domain.wallet,
      domain.accountsApi,
      domain.transactionsApi,
      domain.assetsApi,
      PeerDatabase.NoOp,
      new ConcurrentHashMap(),
      (blockId, _) => Task(domain.blockchain.removeAfter(blockId).map(_ => ())),
      domain.utxPool,
      TestMiner.SafelyDisabled,
      null,
      null,
      null,
      null,
      configObject,
      domain.rocksDBWriter,
      new RouteTimeout(60.seconds)(using sharedScheduler),
      sharedScheduler
    )

  private val route = seal(debugApiRoute.route)

  routePath("/stateHash") - {
    "works" - {
      "with DeterministicFinality activated on genesis block" in {
        // Append first block to be able to request stateHash
        domain.appendBlock()

        // Assert after DeterministicFinality feature activation
        domain.blockchain.isFeatureActivated(BlockchainFeatures.DeterministicFinality, domain.blockchain.height) shouldBe true
        val genesisHeight      = 1
        val genesisBlockHeader = domain.blockchain.blockHeader(genesisHeight).value
        val expectedResponse = Json.obj(
          "stateHash"         -> "c14b7b14aaee11890fc243342c2bc36640f287da21bb891906c56c4bef094348",
          "dccBalanceHash"  -> "f5f5d3d02528e2f4708b23b1410aae4eca91e333846552db57c75a62da7c36f4",
          "assetBalanceHash"  -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "dataEntryHash"     -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "accountScriptHash" -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "assetScriptHash"   -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseBalanceHash"  -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseStatusHash"   -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "sponsorshipHash"   -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "aliasHash"         -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          // Note: "nextCommittedGeneratorsHash" and "committedGeneratorBalancesHash" fields are present
          "nextCommittedGeneratorsHash"    -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "committedGeneratorBalancesHash" -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "snapshotHash"                   -> "BBYVevXs4DtP5q8bpRHUyhHv5fmhVVhcniQa79B3s4Ar",
          "blockId"                        -> genesisBlockHeader.id().toString,
          "baseTarget"                     -> genesisBlockHeader.header.baseTarget,
          "height"                         -> genesisHeight,
          "version"                        -> Version.VersionString
        )

        Get(routePath(s"/stateHash/$genesisHeight")) ~> ApiKeyHeader ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[JsObject] shouldBe expectedResponse
        }
      }
    }
  }
}
