package com.decentralchain.http

import com.decentralchain.TestValues
import com.decentralchain.api.common.CommonGeneratorsApi
import com.decentralchain.api.http.{GeneratorsApiRoute, RouteTimeout}
import com.decentralchain.block.{Block, BlockEndorsement, FinalizationVoting}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.db.WithState
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.settings.{WalletSettings, DCCSettings}
import com.decentralchain.state.{GeneratorIndex, Height, diffs}
import com.decentralchain.test.*
import com.decentralchain.transaction.{CommitToGenerationTransaction, TxHelpers}
import com.decentralchain.utils.SharedSchedulerMixin
import com.decentralchain.wallet.Wallet
import org.apache.pekko.http.scaladsl.model.StatusCodes
import play.api.libs.json.*

import scala.concurrent.duration.*

class GeneratorsApiRouteSpec extends RouteSpec("/generators") with RestAPISettingsHelper with SharedDomain with SharedSchedulerMixin {
  override def settings: DCCSettings = {
    val orig = DomainPresets.DeterministicFinality
    orig.copy(
      restAPISettings = restAPISettings,
      blockchainSettings = orig.blockchainSettings.copy(
        functionalitySettings = orig.blockchainSettings.functionalitySettings.copy(generationPeriodLength = 3)
      )
    )
  }

  private val wallet                      = Wallet(WalletSettings(file = None, password = None, Some(ByteStr("seed".getBytes()))))
  private val Seq(generator1, generator2) = wallet.generateNewAccounts(2)
  private val depositAndFee               = CommitToGenerationTransaction.DepositInDcclets + TestValues.commitToGenerationFee
  private val initBalance                 = diffs.ENOUGH_AMT + depositAndFee

  override def genesisBalances: Seq[WithState.AddrWithBalance] =
    Seq(generator1, generator2).map(x => AddrWithBalance(x.toAddress, initBalance)) ++ AddrWithBalance.enoughBalances(TxHelpers.defaultSigner)

  private val api = CommonGeneratorsApi(domain.rdb, domain.blockchainUpdater)
  private val route = seal(
    GeneratorsApiRoute(
      restAPISettings,
      domain.blockchain,
      api,
      domain.testTime,
      new RouteTimeout(60.seconds)(using sharedScheduler)
    ).route
  )

  routePath("/at/{height}") in {
    val generationPeriod = domain.blockchain.currentGenerationPeriod.value.next

    val txns   = Seq(generator1, generator2).map(x => TxHelpers.commitToGeneration(generationPeriod.start, sender = x))
    val block2 = domain.createBlock(Block.PlainBlockVersion, txns, strictTime = true) // defaultSigner

    domain.appender.appendBlock(block2)
    domain.appendBlock()

    log.debug("Before period")
    Get(routePath(s"/at/${domain.blockchain.height}")) ~> route ~> check {
      responseAs[JsValue] shouldBe Json.arr()
    }

    domain.appender.appendBlock(domain.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = generator1, strictTime = true))

    log.debug("Before conflict endorsement")
    val expectedBeforeConflictHeight = Json.arr(
      Json.obj(
        "address"       -> generator1.toAddress.toString,
        "balance"       -> (initBalance - depositAndFee),
        "transactionId" -> txns.head.id().toString
      ),
      Json.obj(
        "address"       -> generator2.toAddress.toString,
        "balance"       -> (initBalance - depositAndFee),
        "transactionId" -> txns.last.id().toString
      )
    )
    Get(routePath(s"/at/${domain.blockchain.height}")) ~> route ~> check {
      responseAs[JsValue] shouldBe expectedBeforeConflictHeight
    }

    log.debug("At conflict endorsement")
    val otherFinalizedBlockId = TxHelpers.randomBlockId
    val block5 = domain.createBlock(
      Block.PlainBlockVersion,
      txs = Nil,
      strictTime = true,
      generator = generator1,
      finalizationVoting = Some(
        FinalizationVoting(
          valid = Nil,
          finalizedHeight = Height(1),
          aggregatedEndorsement = None,
          conflict = Vector(
            BlockEndorsement.signed(
              BlsKeyPair(generator2.privateKey),
              GeneratorIndex(1),
              otherFinalizedBlockId,
              finalizedHeight = Height(1),
              endorsedId = domain.blockchain.lastBlockId.value
            )
          )
        )
      )
    )
    domain.appender.appendBlock(block5)
    val conflictEndorsementHeight = domain.blockchain.height
    val expectedOnConflictHeight = Json.arr(
      Json.obj(
        "address"       -> generator1.toAddress.toString,
        "balance"       -> (initBalance - depositAndFee),
        "transactionId" -> txns.head.id().toString
      ),
      Json.obj(
        "address"        -> generator2.toAddress.toString,
        "balance"        -> 0,
        "transactionId"  -> txns.last.id().toString,
        "conflictHeight" -> conflictEndorsementHeight
      )
    )
    Get(routePath(s"/at/${domain.blockchain.height}")) ~> route ~> check {
      responseAs[JsValue] shouldBe expectedOnConflictHeight
    }

    log.debug("Request at future height")
    Get(routePath(s"/at/${domain.blockchain.height + 1}")) ~> route ~> check {
      status shouldBe StatusCodes.NotFound
    }

    log.debug("After conflict endorsement")
    domain.appender.appendBlock(domain.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = generator1, strictTime = true))
    Get(routePath(s"/at/${domain.blockchain.height}")) ~> route ~> check {
      responseAs[JsValue] shouldBe expectedOnConflictHeight
    }

    log.debug("After conflict endorsement, request before endorsement height")
    Get(routePath(s"/at/${conflictEndorsementHeight - 1}")) ~> route ~> check {
      responseAs[JsValue] shouldBe expectedBeforeConflictHeight
    }

    log.debug("After conflict endorsement, request at endorsement height")
    Get(routePath(s"/at/$conflictEndorsementHeight")) ~> route ~> check {
      responseAs[JsValue] shouldBe expectedOnConflictHeight
    }

    log.debug("After conflict endorsement, request at future height")
    Get(routePath(s"/at/${domain.blockchain.height + 1}")) ~> route ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }
}
