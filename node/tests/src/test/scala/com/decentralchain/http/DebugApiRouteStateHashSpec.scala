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
import com.decentralchain.state.{Blockchain, Height}
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.utils.SharedSchedulerMixin
import monix.eval.Task
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.OptionValues
import play.api.libs.json.{JsObject, Json}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

class DebugApiRouteStateHashSpec
    extends RouteSpec("/debug")
    with RestAPISettingsHelper
    with TestWallet
    with NTPTime
    with SharedDomain
    with OptionValues
    with SharedSchedulerMixin {

  private lazy val deterministicFinalityActivationHeight = 5

  override def settings: DCCSettings = DomainPresets.TransactionStateSnapshot
    .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
    .copy(
      dbSettings = DomainPresets.TransactionStateSnapshot.dbSettings.copy(storeStateHashes = true),
      restAPISettings = restAPISettings
    )
    .setFeaturesHeight(BlockchainFeatures.DeterministicFinality -> deterministicFinalityActivationHeight)
    .configure(_.copy(generationPeriodLength = 2))

  private val configObject: ConfigObject = settings.config.root()

  private val secondGenerator = TxHelpers.signer(906)
  private val thirdGenerator  = TxHelpers.signer(907)

  override def genesisBalances: Seq[AddrWithBalance] = Seq(
    AddrWithBalance(TxHelpers.defaultSigner.toAddress, 10_000.dcc),
    AddrWithBalance(secondGenerator.toAddress, 11_000.dcc),
    AddrWithBalance(thirdGenerator.toAddress, 12_000.dcc)
  )

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
      "before and after DeterministicFinality activation" in {
        // Append first block to be able to request stateHash
        domain.appendBlock()

        // Assert after DeterministicFinality feature activation
        domain.blockchain.isFeatureActivated(BlockchainFeatures.DeterministicFinality, domain.blockchain.height) shouldBe false
        val beforeFinalityHeight = domain.blockchain.height - 1
        val beforeFinalityHeader = domain.blockchain.blockHeader(beforeFinalityHeight).value
        val expectedResponseBefore = Json.obj(
          "stateHash"         -> "8655f9cfbfd3fca3df1f05392d419aeee2aded1998f6f15f572e27e7ee50bb39",
          "dccBalanceHash"  -> "5dc9606d7c9c26b124dd7e70e1b633a84fa08cf9c5bc2b040f55fd31a898900e",
          "assetBalanceHash"  -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "dataEntryHash"     -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "accountScriptHash" -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "assetScriptHash"   -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseBalanceHash"  -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseStatusHash"   -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "sponsorshipHash"   -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "aliasHash"         -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          // Note: "nextCommittedGeneratorsHash" and "committedGeneratorBalancesHash" fields are not present
          "snapshotHash" -> "HPpCdizo6ZjPbWx3hYM1jSh5b42TWUmvPykJM6faYGy8",
          "blockId"      -> beforeFinalityHeader.id().toString,
          "baseTarget"   -> beforeFinalityHeader.header.baseTarget,
          "height"       -> beforeFinalityHeight,
          "version"      -> Version.VersionString
        )

        Get(routePath(s"/stateHash/$beforeFinalityHeight")) ~> ApiKeyHeader ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[JsObject] shouldBe expectedResponseBefore
        }

        Get(routePath(s"/stateHash/last")) ~> ApiKeyHeader ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[JsObject] shouldBe expectedResponseBefore
        }

        // Fast-forward to DeterministicFinality feature activation
        val currentHeight = domain.blockchain.height
        val targetHeight  = deterministicFinalityActivationHeight
        if (currentHeight < targetHeight) {
          val blocksToAdd = targetHeight - currentHeight
          Range.inclusive(0, blocksToAdd).foreach(_ => domain.appendBlock())
        }

        // Assert after DeterministicFinality feature activation
        val afterFinalityHeight = domain.blockchain.height - 1
        domain.blockchain.isFeatureActivated(BlockchainFeatures.DeterministicFinality, afterFinalityHeight) shouldBe true

        val commitTxDefault = TxHelpers.commitToGeneration(generationPeriodStart = Height(8), sender = TxHelpers.defaultSigner)
        val commitTxSecond  = TxHelpers.commitToGeneration(generationPeriodStart = Height(8), sender = secondGenerator)
        val commitTxThird   = TxHelpers.commitToGeneration(generationPeriodStart = Height(8), sender = thirdGenerator)
        domain.appendBlock(commitTxDefault, commitTxSecond, commitTxThird)
        domain.appendBlock()

        // Assert after commitment, before generation period
        val afterGeneratingBalanceUpdateHeight = domain.blockchain.height - 1
        val afterGeneratingBalanceUpdateHeader = domain.blockchain.blockHeader(afterGeneratingBalanceUpdateHeight).value
        val expectedResponseAfter = Json.obj(
          "stateHash"                      -> "fed960ad5bdbe4490144ef14755df30f8b7d15ca04ba0cd19f72c1fb3ec5db63",
          "dccBalanceHash"               -> "72c993833df29fc305a3faa497ca40c34edb1f1278b281f1d28db73f1777decc",
          "assetBalanceHash"               -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "dataEntryHash"                  -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "accountScriptHash"              -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "assetScriptHash"                -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseBalanceHash"               -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseStatusHash"                -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "sponsorshipHash"                -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "aliasHash"                      -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "nextCommittedGeneratorsHash"    -> "0c081cdc089066b9679d6a6abe7e30f415dca425a8d4442062748a73013a5aa9", // Note: non-empty
          "committedGeneratorBalancesHash" -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "snapshotHash"                   -> "sKtnCgDnE24G5fvcDmrrNyYJRbf2mXCoVhyG9eLxS2i",
          "blockId"                        -> afterGeneratingBalanceUpdateHeader.id().toString,
          "baseTarget"                     -> afterGeneratingBalanceUpdateHeader.header.baseTarget,
          "height"                         -> afterGeneratingBalanceUpdateHeight,
          "version"                        -> Version.VersionString
        )

        Get(routePath(s"/stateHash/$afterGeneratingBalanceUpdateHeight")) ~> ApiKeyHeader ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[JsObject] shouldBe expectedResponseAfter
        }

        // Note: the generating balances are used on this height (parent block for heightOnGenerationPeriod)
        domain.blockchain.generatingBalance(TxHelpers.defaultSigner.toAddress) shouldBe 991200200000L
        domain.blockchain.generatingBalance(secondGenerator.toAddress) shouldBe 1089999000000L
        domain.blockchain.generatingBalance(thirdGenerator.toAddress) shouldBe 1189999000000L

        // Fast-forward to generation period change
        domain.appendBlock() // heightOnGenerationPeriod
        domain.appendBlock() // add 1 more block for API requests

        // Assert after commitment, on generation period
        val heightOnGenerationPeriod = domain.blockchain.height - 1
        val headerOnGenerationPeriod = domain.blockchain.blockHeader(heightOnGenerationPeriod).value
        val expectedResponseAfter2 = Json.obj(
          "stateHash"                      -> "6e30ca00b60565c83a94d395447264ba8835ce74ff97ab489351ab746c2a8459",
          "dccBalanceHash"               -> "3c69d657237b1a63da4eba0364a557ff1b5920b305f731efba1c947412b38cc5",
          "assetBalanceHash"               -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "dataEntryHash"                  -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "accountScriptHash"              -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "assetScriptHash"                -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseBalanceHash"               -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "leaseStatusHash"                -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "sponsorshipHash"                -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "aliasHash"                      -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "nextCommittedGeneratorsHash"    -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
          "committedGeneratorBalancesHash" -> "1d11bfc3807f33671cb8cd0204ebafc646cdf4bc59b360e826a60bc008ab28b4", // Note: non-empty
          "snapshotHash"                   -> "Gb16GzKGoaKoh7Qz75enmwvizRop7VwHwr8YQnyXkheC",
          "blockId"                        -> headerOnGenerationPeriod.id().toString,
          "baseTarget"                     -> headerOnGenerationPeriod.header.baseTarget,
          "height"                         -> heightOnGenerationPeriod,
          "version"                        -> Version.VersionString
        )

        Get(routePath(s"/stateHash/last")) ~> ApiKeyHeader ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[JsObject] shouldBe expectedResponseAfter2
        }
      }
    }
  }
}
