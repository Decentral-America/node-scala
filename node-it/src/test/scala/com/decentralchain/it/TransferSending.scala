package com.decentralchain.it

import java.util.concurrent.ThreadLocalRandom

import com.google.common.primitives.Ints
import com.typesafe.config.Config
import com.decentralchain.account.*
import com.decentralchain.api.http.requests.TransferRequest
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.TransferSending.Req
import com.decentralchain.it.api.AsyncHttpApi.*
import com.decentralchain.it.api.Transaction
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.transfer.*
import com.decentralchain.utils.ScorexLogging
import org.scalatest.Suite
import play.api.libs.json.Json.toJson
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future

object TransferSending {
  case class Req(senderSeed: String, targetAddress: String, amount: Long, fee: Long)
}

trait TransferSending extends ScorexLogging {
  this: Suite & Nodes =>

  import scala.concurrent.ExecutionContext.Implicits.global

  def generateTransfersFromAccount(n: Int, accountAddress: String): Seq[Req] = {
    val fee      = 100000 + 400000 // + 400000 for scripted accounts
    val seedSize = 32

    val srcSeed = NodeConfigs.Default
      .collectFirst {
        case x if x.getString("address") == accountAddress => x.getString("account-seed")
      }
      .getOrElse(throw new RuntimeException(s"Can't find address '$accountAddress' in nodes.conf"))

    val sourceAndDest = (1 to n).map { _ =>
      val destPk = Array.fill[Byte](seedSize)(ThreadLocalRandom.current().nextInt(Byte.MaxValue).toByte)
      Address.fromPublicKey(PublicKey(destPk)).toString
    }

    val requests = sourceAndDest.foldLeft(List.empty[Req]) { case (rs, dstAddr) =>
      rs :+ Req(srcSeed, dstAddr, fee, fee)
    }

    requests
  }

  def generateTransfersBetweenAccounts(n: Int, balances: Map[Config, Long]): Seq[Req] = {
    val fee = 100000
    val srcDest = balances.toSeq
      .map { case (config, _) =>
        val accountSeed = config.getString("account-seed")
        (config, KeyPair(Base58.decode(accountSeed)))
      }

    val sourceAndDest = (1 to n).map { _ =>
      val Seq((srcConfig, _), (_, destPrivateKey)) = (new scala.util.Random(ThreadLocalRandom.current()).shuffle(srcDest).take(2): @unchecked)
      (srcConfig, destPrivateKey.toAddress.toString)
    }

    val requests = sourceAndDest.foldLeft(List.empty[Req]) { case (rs, (srcConfig, destAddr)) =>
      val a              = ThreadLocalRandom.current().nextDouble()
      val b              = balances(srcConfig)
      val transferAmount = (1e-8 + a * 1e-9 * b).toLong
      if (transferAmount < 0) log.warn(s"Negative amount: (1e-8 + $a * 1e-8 * $b) = $transferAmount")
      rs :+ Req(srcConfig.getString("account-seed"), destAddr, Math.max(transferAmount, 1L), fee)
    }

    requests
  }

  def generateTransfersToRandomAddresses(n: Int, excludeSrcAddresses: Set[String]): Seq[Req] = {
    val fee = 100000

    val seeds = NodeConfigs.Default.collect {
      case config if !excludeSrcAddresses.contains(config.getString("address")) => config.getString("account-seed")
    }

    val prefix = Ints.toByteArray(ThreadLocalRandom.current().nextInt())

    val sourceAndDest = (1 to n).map { id =>
      val srcSeed  = new scala.util.Random(ThreadLocalRandom.current()).shuffle(seeds).head
      val destPk   = prefix ++ Ints.toByteArray(id) ++ new Array[Byte](24)
      val destAddr = Address.fromPublicKey(PublicKey(destPk)).toString

      (srcSeed, destAddr)
    }
    val requests = sourceAndDest.foldLeft(List.empty[Req]) { case (rs, (srcSeed, dstAddr)) =>
      rs :+ Req(srcSeed, dstAddr, fee, fee)
    }

    requests
  }

  def balanceForNode(n: Node): Future[(String, Long)] = n.balance(n.address).map(b => n.address -> b.balance)

  def processRequests(requests: Seq[Req], includeAttachment: Boolean = false): Future[Seq[Transaction]] = {
    val start = System.currentTimeMillis() - requests.size
    val signedTransfers = requests.zipWithIndex
      .map { case (x, i) =>
        createSignedTransferRequest(
          TransferTransaction
            .selfSigned(
              version = 2.toByte,
              sender = KeyPair(Base58.decode(x.senderSeed)),
              recipient = AddressOrAlias.fromString(x.targetAddress).explicitGet(),
              asset = Dcc,
              amount = x.amount,
              feeAsset = Dcc,
              fee = x.fee,
              attachment =
                if (includeAttachment)
                  ByteStr(Array.fill(TransferTransaction.MaxAttachmentSize)(ThreadLocalRandom.current().nextInt().toByte))
                else ByteStr.empty,
              timestamp = start + i
            )
            .explicitGet()
        )
      }

    signedTransfers.zip(Iterator.continually(nodes).flatten).foldLeft(Future.successful(Seq.empty[Transaction])) {
      case (resultFuture, (transferRequest, node)) =>
        for {
          result <- resultFuture
          tx     <- node.signedBroadcast(toJson(transferRequest).as[JsObject] ++ Json.obj("type" -> TransferTransaction.typeId.toInt))
        } yield tx +: result
    }
  }

  protected def createSignedTransferRequest(tx: TransferTransaction): TransferRequest = {
    import tx.*
    TransferRequest(
      Some(2.toByte),
      None,
      Some(tx.sender.toString),
      recipient.toString,
      Some(assetId),
      amount.value,
      Some(feeAssetId),
      fee.value,
      Some(attachment),
      Some(timestamp),
      None,
      Some(proofs)
    )
  }

}
