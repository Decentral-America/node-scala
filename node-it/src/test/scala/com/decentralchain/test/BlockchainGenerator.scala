package com.decentralchain.test

import com.decentralchain.Exporter.IO
import com.decentralchain.account.KeyPair
import com.decentralchain.block.{Block, BlockHeader}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.consensus.PoSSelector
import com.decentralchain.database.RDB
import com.decentralchain.events.{BlockchainUpdateTriggers, UtxEvent}
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.StorageFactory
import com.decentralchain.lang.ValidationError
import com.decentralchain.mining.{ForgeAttemptResult, Miner, MinerImpl}
import com.decentralchain.settings.{DBSettings, DCCSettings}
import com.decentralchain.state.appender.BlockAppender
import com.decentralchain.state.{BlockEndorser, EndorsementStorage}
import com.decentralchain.test.BlockchainGenerator.{GenBlock, GenTx}
import com.decentralchain.transaction.*
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.assets.*
import com.decentralchain.transaction.assets.exchange.ExchangeTransaction
import com.decentralchain.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.decentralchain.transaction.smart.{InvokeScriptTransaction, SetScriptTransaction}
import com.decentralchain.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.decentralchain.utils.generator.FakeTime
import com.decentralchain.utils.{Schedulers, ScorexLogging, Time}
import com.decentralchain.utx.UtxPoolImpl
import com.decentralchain.wallet.Wallet
import com.decentralchain.{Exporter, checkGenesis, crypto}
import io.netty.channel.group.DefaultChannelGroup
import monix.execution.ExecutionModel.SynchronousExecution
import monix.execution.Scheduler
import monix.reactive.subjects.ConcurrentSubject
import org.apache.commons.io.FileUtils
import org.web3j.crypto.{ECKeyPair, RawTransaction}

import java.io.BufferedOutputStream
import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.language.reflectiveCalls
import scala.util.{Failure, Success, Using}

/** Usage example: <pre>object Example extends App {
  *  val dccSettings = Application.loadApplicationConfig(Some(new File("path-to-config-file")))
  *  val generator = new BlockchainGenerator(dccSettings)
  *  val sender = KeyPair("123".getBytes)
  *  val recipient = Address.fromString("3FddHK1Y3vPdcVKZshWCWea4gS5th6G1UE6").getOrElse(sender.toAddress)
  *  val genBlocks = (1 to 10).map { idx =>
  *    GenBlock(
  *      (1 to 5).map(txIdx => GenTx(TxHelpers.transfer(sender, recipient, amount = (idx * 10 + txIdx) * 100000000L), Right(sender))),
  *      signer = sender
  *    )
  *  }
  *  generator.generateBinaryFile(genBlocks)
  *
  *  // only if you use Application.loadApplicationConfig method to create DCCSettings object
  *  Try(Await.result(Kamon.stopModules(), 10.seconds))
  *  Metrics.shutdown()
  * }
  * </pre>
  */
class BlockchainGenerator(dccSettings: DCCSettings) extends ScorexLogging {
  private given scheduler: Scheduler = Schedulers.singleThread("grpc", executionModel = SynchronousExecution)

  private val settings: DCCSettings = dccSettings.copy(minerSettings = dccSettings.minerSettings.copy(quorum = 0))

  def generateDb(genBlocks: Iterator[GenBlock], dbDirPath: String = settings.dbSettings.directory): Unit =
    generateBlockchain(genBlocks, settings.dbSettings.copy(directory = dbDirPath))

  def generateBinaryFile(genBlocks: Iterator[GenBlock]): Unit = {
    val targetHeight = genBlocks.size + 1
    log.info(s"Exporting to $targetHeight")
    val outputFilename = s"blockchain-$targetHeight"
    log.info(s"Output file: $outputFilename")

    Using.resource {
      Exporter.IO.createOutputStream(outputFilename) match {
        case Success(output) => output
        case Failure(ex)     =>
          log.error(s"Failed to create file '$outputFilename': $ex")
          throw ex
      }
    } { output =>
      Using.resource(new BufferedOutputStream(output, 10 * 1024 * 1024)) { bos =>
        val dbDirPath = Files.createTempDirectory("generator-temp-db")
        generateBlockchain(
          genBlocks,
          settings.dbSettings.copy(directory = dbDirPath.toString),
          block => IO.exportBlock(bos, Some(block), legacy = true)
        )
        log.info(s"Finished exporting $targetHeight blocks")
        FileUtils.deleteDirectory(dbDirPath.toFile)
      }
    }
  }

  private def generateBlockchain(genBlocks: Iterator[GenBlock], dbSettings: DBSettings, exportToFile: Block => Unit = _ => ()): Unit = {
    val scheduler = Schedulers.singleThread("appender")
    val time      = new FakeTime(settings.blockchainSettings.genesisSettings.timestamp)
    Using.Manager { use =>
      val db                         = use(RDB.open(dbSettings))
      val (blockchain, rdbWriterRaw) = StorageFactory(settings, db, time, BlockchainUpdateTriggers.noop)
      use(rdbWriterRaw)
      val utxPool     = use(new UtxPoolImpl(time, blockchain, settings.utxSettings, settings.maxTxErrorLogSize, settings.minerSettings.enable))
      val pos         = PoSSelector(blockchain, settings.synchronizationSettings.maxBaseTarget)
      val extAppender = BlockAppender(blockchain, time, utxPool, pos, BlockEndorser.Disabled, scheduler)(_, None)
      val utxEvents   = ConcurrentSubject.publish[UtxEvent]

      val miner = new MinerImpl(
        new DefaultChannelGroup("", null),
        blockchain,
        settings,
        time,
        utxPool,
        BlockEndorser.Disabled,
        EndorsementStorage.Disabled,
        Wallet(settings.walletSettings),
        PoSSelector(blockchain, None),
        scheduler,
        scheduler,
        utxEvents.collect { case _: UtxEvent.TxAdded => () }
      )

      checkGenesis(settings, blockchain, Miner.StrictDisabledMiner)
      val result = genBlocks.foldLeft[Either[ValidationError, Unit]](Right(())) {
        case (res @ Left(_), _) => res
        case (_, genBlock)      =>
          time.time = miner.nextBlockGenerationTime(blockchain, blockchain.lastBlockHeader.get, genBlock.signer).explicitGet()
          val correctedTimeTxs = genBlock.txs.map(correctTxTimestamp(_, time))

          miner.forgeBlock(genBlock.signer) match {
            case ForgeAttemptResult.Success(block, _) =>
              for {
                blockWithTxs <- Block.buildAndSign(
                  block.header.version,
                  block.header.timestamp,
                  block.header.reference,
                  block.header.baseTarget,
                  block.header.generationSignature,
                  correctedTimeTxs,
                  genBlock.signer,
                  block.header.featureVotes,
                  block.header.rewardVote,
                  block.header.stateHash,
                  block.header.challengedHeader,
                  block.header.finalizationVoting
                )
                _ <- Await
                  .result(extAppender(blockWithTxs).runAsyncLogErr, Duration.Inf)
              } yield exportToFile(blockWithTxs)

            case ForgeAttemptResult.TemporaryFailure(err) => Left(GenericError(err))
            case ForgeAttemptResult.PermanentFailure(err) => Left(GenericError(err))
          }
      }
      result match {
        case Right(_) =>
          if (blockchain.isFeatureActivated(BlockchainFeatures.NG) && blockchain.liquidBlockMeta.nonEmpty) {
            val lastHeader  = blockchain.lastBlockHeader.get.header
            val pseudoBlock = Block(
              BlockHeader(
                blockchain.blockVersionAt(blockchain.height),
                time.getTimestamp() + settings.blockchainSettings.genesisSettings.averageBlockDelay.toMillis,
                blockchain.lastBlockId.get,
                lastHeader.baseTarget,
                lastHeader.generationSignature,
                lastHeader.generator,
                featureVotes = Nil,
                rewardVote = 0,
                transactionsRoot = ByteStr.empty,
                stateHash = None,
                challengedHeader = None,
                finalizationVoting = None
              ),
              ByteStr.empty,
              Nil
            )
            blockchain.processBlock(pseudoBlock, ByteStr.empty, snapshot = None, generatorSet = Seq.empty, verify = false)
          }
        case Left(err) => log.error(s"Error appending block: $err")
      }
    }.get
  }

  private def correctTxTimestamp(genTx: GenTx, time: Time): Transaction =
    genTx match {
      case GenTx(t: BurnTransaction, Right(signer))        => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: CreateAliasTransaction, Right(signer)) => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: DataTransaction, Right(signer))        => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: EthereumTransaction, Left(signer))     =>
        val correctedTimeRawTx = RawTransaction.createTransaction(
          BigInt(time.getTimestamp()).bigInteger,
          t.underlying.getGasPrice,
          t.underlying.getGasLimit,
          t.underlying.getTo,
          t.underlying.getValue,
          t.underlying.getData
        )
        EthTxGenerator.signRawTransaction(signer, t.chainId)(correctedTimeRawTx)
      case GenTx(t: ExchangeTransaction, Right(signer))     => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: InvokeScriptTransaction, Right(signer)) => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: IssueTransaction, Right(signer))        => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: LeaseCancelTransaction, Right(signer))  => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: LeaseTransaction, Right(signer))        => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: MassTransferTransaction, Right(signer)) => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: PaymentTransaction, Right(signer))      =>
        t.copy(timestamp = time.getTimestamp(), signature = crypto.sign(signer.privateKey, t.bodyBytes()))
      case GenTx(t: ReissueTransaction, Right(signer))         => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: SetAssetScriptTransaction, Right(signer))  => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: SetScriptTransaction, Right(signer))       => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: SponsorFeeTransaction, Right(signer))      => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: TransferTransaction, Right(signer))        => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t: UpdateAssetInfoTransaction, Right(signer)) => t.copy(timestamp = time.getTimestamp()).signWith(signer.privateKey)
      case GenTx(t, _)                                         => t
    }
}

object BlockchainGenerator {
  case class GenBlock(
      txs: Seq[GenTx],
      signer: KeyPair = TxHelpers.defaultSigner,
      version: Byte = Block.ProtoBlockVersion
  )
  case class GenTx(
      tx: Transaction,
      signer: Either[ECKeyPair, KeyPair]
  )
}
