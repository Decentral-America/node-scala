package com.decentralchain.mining

import com.decentralchain.account.{KeyPair, SeedKeyPair}
import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.lang.ValidationError
import com.decentralchain.lang.directives.values.V5
import com.decentralchain.lang.script.ContractScript.ContractScriptImpl
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.lang.v1.compiler.Terms.CONST_STRING
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.settings.{WalletSettings, DCCSettings}
import com.decentralchain.state.BlockEndorser
import com.decentralchain.state.BlockchainUpdaterImpl.BlockApplyResult
import com.decentralchain.state.appender.BlockAppender
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.{TxHelpers, TxVersion}
import com.decentralchain.utx.UtxPoolImpl
import com.decentralchain.wallet.Wallet
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.concurrent.duration.*

class MinerAccountScriptRestrictionsTest extends PropSpec with WithDomain {

  type Appender = Block => Task[Either[ValidationError, BlockApplyResult]]

  val time: TestTime            = TestTime()
  val minerAcc: SeedKeyPair     = TxHelpers.signer(1)
  val invoker: KeyPair          = TxHelpers.signer(2)
  val allowedRecipient: KeyPair = TxHelpers.signer(3)

  val dataKey = "testKey"

  property("miner account can have any script after RideV6 feature activation") {
    Seq(
      (dAppScriptWithVerifier, true, true),
      (dAppScriptWithoutVerifier, true, false),
      (accountScript, false, true)
    ).foreach { case (script, hasCallable, hasVerifier) =>
      val checkCallableTxCount = if (hasCallable) 2 else 0
      val checkVerifierTxCount = if (hasVerifier) 1 else 0
      val activationHeight     = 3 + checkCallableTxCount + checkVerifierTxCount

      withDomain(
        DomainPresets.RideV5.setFeaturesHeight((BlockchainFeatures.RideV6, activationHeight)),
        AddrWithBalance.enoughBalances(minerAcc, invoker)
      ) { d =>
        withMiner(d) { (miner, appender, scheduler) =>
          d.appendBlock(setScript(script))
          if (hasCallable) {
            d.appendAndAssertSucceed(
              TxHelpers.invoke(minerAcc.toAddress, Some("c"), Seq(CONST_STRING("invoker").explicitGet()), invoker = invoker)
            )
            d.accountsApi.data(minerAcc.toAddress, dataKey).get.value shouldBe "invoker"
            d.appendAndAssertSucceed(
              TxHelpers.invoke(minerAcc.toAddress, Some("c"), Seq(CONST_STRING("miner").explicitGet()), invoker = minerAcc)
            )
            d.accountsApi.data(minerAcc.toAddress, dataKey).get.value shouldBe "miner"
          }
          if (hasVerifier) {
            d.appendAndAssertSucceed(TxHelpers.transfer(minerAcc, allowedRecipient.toAddress))
            d.appendAndCatchError(TxHelpers.transfer(minerAcc, invoker.toAddress)).toString should include("TransactionNotAllowedByScript")
          }
          miner.getNextBlockGenerationOffset(minerAcc) should produce(errMsgBeforeRideV6)
          forgeAndAppendBlock(d, miner, appender)(using scheduler) should produce(errMsgBeforeRideV6)

          d.appendBlock()
          miner.getNextBlockGenerationOffset(minerAcc) should beRight
          forgeAndAppendBlock(d, miner, appender)(using scheduler) should beRight
        }
      }
    }
  }

  private def errMsgBeforeRideV6 =
    s"Account(${minerAcc.toAddress}) is scripted and not allowed to forge blocks"

  private def withMiner(d: Domain)(f: (MinerImpl, Appender, Scheduler) => Unit): Unit = {
    val defaultSettings = DCCSettings.default()
    val dccSettings   = defaultSettings.copy(minerSettings = defaultSettings.minerSettings.copy(quorum = 0))

    val utx = new UtxPoolImpl(
      time,
      d.blockchainUpdater,
      dccSettings.utxSettings,
      dccSettings.maxTxErrorLogSize,
      isMiningEnabled = dccSettings.minerSettings.enable
    )
    val appenderScheduler = Scheduler.singleThread("appender")

    val miner = new MinerImpl(
      new DefaultChannelGroup(GlobalEventExecutor.INSTANCE),
      d.blockchainUpdater,
      dccSettings,
      time,
      utx,
      BlockEndorser.Disabled,
      d.endorsementStorage,
      Wallet(WalletSettings(None, Some("123"), Some(ByteStr(minerAcc.seed)))),
      d.posSelector,
      Scheduler.singleThread("miner"),
      appenderScheduler,
      Observable.empty
    )

    val appender = BlockAppender(d.blockchainUpdater, time, utx, d.posSelector, BlockEndorser.Disabled, appenderScheduler)(_, None)

    f(miner, appender, appenderScheduler)

    appenderScheduler.shutdown()
    utx.close()
  }

  private def forgeAndAppendBlock(d: Domain, miner: MinerImpl, appender: Appender)(implicit scheduler: Scheduler) = {
    time.setTime(
      d.lastBlock.header.timestamp + d.posSelector
        .getValidBlockDelay(d.blockchain.height, minerAcc, d.lastBlock.header.baseTarget, d.blockchain.generatingBalance(minerAcc.toAddress))
        .explicitGet()
    )

    for {
      forge <- miner.forgeBlock(minerAcc).toEither
      r     <- appender(forge.newBlock).runSyncUnsafe(10.seconds)
    } yield r
  }

  private def setScript(script: Script): SetScriptTransaction =
    TxHelpers.setScript(acc = minerAcc, script = script, fee = 0.01.waves, version = TxVersion.V2)

  private def verifierScriptStr: String =
    s"""
       |match tx {
       |    case t: TransferTransaction => t.recipient == Address(base58'${allowedRecipient.toAddress}')
       |    case _ => true
       |}
       |""".stripMargin

  private def callableFuncStr: String =
    s"""
       |@Callable(i)
       |func c(value: String) = {
       |  [StringEntry("$dataKey", value)]
       |}""".stripMargin

  private def accountScript: ExprScript =
    TestCompiler(V5).compileExpression(verifierScriptStr)

  private def dAppScriptWithVerifier: ContractScriptImpl = {
    val expr =
      s"""
         |$callableFuncStr
         |
         |@Verifier(tx)
         |func v() = {
         |  $verifierScriptStr
         |}
         |""".stripMargin
    TestCompiler(V5).compileContract(expr)
  }

  private def dAppScriptWithoutVerifier: ContractScriptImpl =
    TestCompiler(V5).compileContract(callableFuncStr)
}
