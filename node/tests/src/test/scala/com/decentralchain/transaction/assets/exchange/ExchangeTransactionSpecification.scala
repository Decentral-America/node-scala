package com.decentralchain.transaction.assets.exchange

import com.decentralchain.account.{KeyPair, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base64
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.lang.ValidationError
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.TxValidationError.{GenericError, OrderValidationError}
import com.decentralchain.transaction.assets.exchange.AssetPair.extractAssetId
import com.decentralchain.transaction.serialization.impl.ExchangeTxSerializer
import com.decentralchain.transaction.{Asset, Proofs, TxExchangeAmount, TxMatcherFee, TxOrderPrice, TxVersion}
import com.decentralchain.utils.JsonMatchers
import com.decentralchain.{NTPTime, crypto}
import org.scalacheck.Gen
import play.api.libs.json.Json

import scala.math.pow

//noinspection ScalaStyle
class ExchangeTransactionSpecification extends PropSpec with NTPTime with JsonMatchers {
  val versionsGen: Gen[(Byte, Byte, Byte)] = Gen.oneOf(
    (1.toByte, 1.toByte, 1.toByte),
    (1.toByte, 2.toByte, 2.toByte),
    (1.toByte, 3.toByte, 2.toByte),
    (2.toByte, 2.toByte, 2.toByte),
    (2.toByte, 3.toByte, 2.toByte),
    (3.toByte, 3.toByte, 2.toByte)
  )

  val preconditions: Gen[(KeyPair, KeyPair, KeyPair, AssetPair, Asset, Asset, (Byte, Byte, Byte))] =
    for {
      sender1                 <- accountGen
      sender2                 <- accountGen
      matcher                 <- accountGen
      pair                    <- assetPairGen
      buyerAnotherAsset       <- assetIdGen.map(Asset.fromCompatId)
      sellerAnotherAsset      <- assetIdGen.map(Asset.fromCompatId)
      buyerMatcherFeeAssetId  <- Gen.oneOf(pair.amountAsset, pair.priceAsset, buyerAnotherAsset)
      sellerMatcherFeeAssetId <- Gen.oneOf(pair.amountAsset, pair.priceAsset, sellerAnotherAsset)
      versions                <- versionsGen
    } yield (sender1, sender2, matcher, pair, buyerMatcherFeeAssetId, sellerMatcherFeeAssetId, versions)

  property("ExchangeTransaction transaction serialization roundtrip") {
    forAll(exchangeTransactionGen) { om =>
      val recovered = ExchangeTransaction.parseBytes(om.bytes()).get
      om.id() shouldBe recovered.id()
      om.buyOrder.idStr() shouldBe recovered.buyOrder.idStr()
      recovered.bytes() shouldEqual om.bytes()
    }
  }

  property("ExchangeV1 decode pre-encoded bytes") {
    // Regenerated for this chain's network id: the original historical bytes embedded sender/order
    // addresses signed for a different (now-invalid) network id, so decoding them threw InvalidAddress
    // before ever reaching this comparison. Freshly built + signed via Order.buy/sell + TxHelpers.exchange
    // (real keys, real signatures) under the current AddressScheme, then captured bytes()/json() verbatim.
    val bytes = Base64.decode(
      "BwAAAMsAAADLUMdZr0J3pVTwzbQssUfNsFO94RF/WwQ7DX1JRLjQYGh5jL1k14xKD7oziypjSWNJQNxOW2AdsQKeAsQeD+BWeQABfx47/wBv/X+A/bGg9ACHZfr/LICA/wH/AX+Af///AQAAAAAAAWWgvAAAAAAAAAAAAgAAAWOH1fVxAAABZCJUvXEAAAAAAAAAATsihpndtB0Jt+MpOFDW1OSKUbsG8u1Uswt0KImfinPdInEKQ2Nipr99L7oDMGtTE+U13trDiz/5gh6iIMJSswwheL31d7bAsIGK0YS+V1oH+a9KdmKzjUQsmkByFiTzA3mMvWTXjEoPujOLKmNJY0lA3E5bYB2xAp4CxB4P4FZ5AAF/Hjv/AG/9f4D9saD0AIdl+v8sgID/Af8Bf4B///8BAAEAAAABKgXyAAAAAAAAAAADAAABY4fV9XEAAAFkIlS9cQAAAAAAAAACgWLS5dpwPdOxlbKJsJ79VhM6LfU3MQuMLRIHj/J1fokYgioq4bRcvr4nvk2hLxZVGdIsWgJfWJP6BqS5nBEVhgAAAAEqBfIAAAAAAAAAAAIAAAAAAAAAAQAAAAAAAAABAAAAAAAAAAEAAAFjh9X1cXmZ5MrSQU+9v9j5zET9WmRwuIcyU2bywl7sfAyi/mUZeWYiYjHMCRFAtMVImW+O2bZbRYx68bLKOL7newxxLYc="
    )
    val json = Json.parse(
      """{
        |  "senderPublicKey" : "9BUoYQYq7K38mkk61q8aMH9kD9fKSVL1Fib7FbH6nUkQ",
        |  "amount" : 2,
        |  "signature" : "3S1YbYjP76vzqXucZPsVEZp1Nu2ZTNMJjNeFKJt3fnm6VM8mRX1bUf66oefvKfTu7p44WwvTnGCkYHnxWAMLV4ZC",
        |  "fee" : 1,
        |  "type" : 7,
        |  "version" : 1,
        |  "sellMatcherFee" : 1,
        |  "sender" : "3DSBL1V7XAsdL66gaCGo9ApvNMAFzqmVPZY",
        |  "feeAssetId" : null,
        |  "proofs" : [ "3S1YbYjP76vzqXucZPsVEZp1Nu2ZTNMJjNeFKJt3fnm6VM8mRX1bUf66oefvKfTu7p44WwvTnGCkYHnxWAMLV4ZC" ],
        |  "price" : 5000000000,
        |  "id" : "Hc1N7YHyE3GwFz67AZNjP3YQBdjCBh2iGQeUndYP5Jka",
        |  "order2" : {
        |    "version" : 1,
        |    "id" : "EmpPuuak9eFhnS9SdXQ3HbtmCXd6yB1XSvsZ7G4sHi34",
        |    "sender" : "3DTuUdm2zW9oABg9dcLS6VaYVFSUmEkqgSA",
        |    "senderPublicKey" : "3FfErcfPcSgV4CEeCTjcUo7bHxbt5tJuknLS1AVFDYAW",
        |    "matcherPublicKey" : "9BUoYQYq7K38mkk61q8aMH9kD9fKSVL1Fib7FbH6nUkQ",
        |    "assetPair" : {
        |      "amountAsset" : null,
        |      "priceAsset" : "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"
        |    },
        |    "orderType" : "sell",
        |    "amount" : 3,
        |    "price" : 5000000000,
        |    "timestamp" : 1526992336241,
        |    "expiration" : 1529584336241,
        |    "matcherFee" : 2,
        |    "signature" : "3b38kjiY6sByFEvcLMfipXtWWpsaFN9bnzu6RBdgW2nBh1nrjLLEuriozeSyz78Vf4B4RvHaMP6EX1XKNcnV4xKX",
        |    "proofs" : [ "3b38kjiY6sByFEvcLMfipXtWWpsaFN9bnzu6RBdgW2nBh1nrjLLEuriozeSyz78Vf4B4RvHaMP6EX1XKNcnV4xKX" ]
        |  },
        |  "order1" : {
        |    "version" : 1,
        |    "id" : "5H6Qv4pgYqMcHFGbPHNhykjFaQqPoxez2NXV2nRZoyHh",
        |    "sender" : "3DWfFRmeEkSKFcjhMpUNx2yMkpXnDvyc9HB",
        |    "senderPublicKey" : "6SKvVVjLaGNBEj98hpyWmZYYreYzGxxVBKwywBBtaEBy",
        |    "matcherPublicKey" : "9BUoYQYq7K38mkk61q8aMH9kD9fKSVL1Fib7FbH6nUkQ",
        |    "assetPair" : {
        |      "amountAsset" : null,
        |      "priceAsset" : "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"
        |    },
        |    "orderType" : "buy",
        |    "amount" : 2,
        |    "price" : 6000000000,
        |    "timestamp" : 1526992336241,
        |    "expiration" : 1529584336241,
        |    "matcherFee" : 1,
        |    "signature" : "2BaF1zZqAukzo6ieFYdqapUWQNFQmbMGBJBBj9bZcJaZjoZxqj5neegCCCoiCJ3DcDFKUfWQFGAQ86HNRjz4Ex5H",
        |    "proofs" : [ "2BaF1zZqAukzo6ieFYdqapUWQNFQmbMGBJBBj9bZcJaZjoZxqj5neegCCCoiCJ3DcDFKUfWQFGAQ86HNRjz4Ex5H" ]
        |  },
        |  "buyMatcherFee" : 1,
        |  "timestamp" : 1526992336241
        |}
        |""".stripMargin
    )

    val tx = ExchangeTxSerializer.parseBytes(bytes).get
    tx.json() should matchJson(json)
    assert(crypto.verify(tx.sellOrder.signature, tx.sellOrder.bodyBytes(), tx.sellOrder.sender), "sellOrder signature should be valid")
    assert(crypto.verify(tx.buyOrder.signature, tx.buyOrder.bodyBytes(), tx.buyOrder.sender), "buyOrder signature should be valid")
    assert(crypto.verify(tx.signature, tx.bodyBytes(), tx.sender), "signature should be valid")
  }

  property("ExchangeV2 decode pre-encoded bytes") {
    // Regenerated for this chain's network id (see ExchangeV1 above for why). Freshly built + signed
    // via Order.buy/sell + TxHelpers.exchange, captured bytes()/json() verbatim.
    val bytes = Base64.decode(
      "AAcCAAAA0QJQx1mvQnelVPDNtCyxR82wU73hEX9bBDsNfUlEuNBgaHmMvWTXjEoPujOLKmNJY0lA3E5bYB2xAp4CxB4P4FZ5AAF/Hjv/AG/9f4D9saD0AIdl+v8sgID/Af8Bf4B///8BAAAAAAABZaC8AAAAAAAAAAACAAABY4fV9XEAAAFkIlS9cQAAAAAAAAABAQABAEASR3SrkhPDA/IkXSyjPujMUtVUDoElH2nmH87i6DAurUZ9cC9xAD/sniz2l32InOhJwk4eiL3tpgeIdmxpwS4PAAAA0QIheL31d7bAsIGK0YS+V1oH+a9KdmKzjUQsmkByFiTzA3mMvWTXjEoPujOLKmNJY0lA3E5bYB2xAp4CxB4P4FZ5AAF/Hjv/AG/9f4D9saD0AIdl+v8sgID/Af8Bf4B///8BAAEAAAABKgXyAAAAAAAAAAADAAABY4fV9XEAAAFkIlS9cQAAAAAAAAACAQABAECYeU5VhJKT1ZB17OXg8hzWYaJyGHpgXvHQMPcDBmYKrbVgHbWzO93hlVyafOUyO498GzYw41F71L8Mn9BveOqPAAAAASoF8gAAAAAAAAAAAgAAAAAAAAABAAAAAAAAAAEAAAAAAAAAAQAAAWOH1fVxAQABAEB7Acia3VH2KAB0G9dORtwlUYBiWdzNS4HH9W72nQB/Cb6D/9mVBQFBeunQYNlFTiwsJh3BP5IcsDSCYy3jGhWA"
    )
    val json = Json.parse(
      """{
        |  "senderPublicKey" : "9BUoYQYq7K38mkk61q8aMH9kD9fKSVL1Fib7FbH6nUkQ",
        |  "amount" : 2,
        |  "fee" : 1,
        |  "type" : 7,
        |  "version" : 2,
        |  "sellMatcherFee" : 1,
        |  "sender" : "3DSBL1V7XAsdL66gaCGo9ApvNMAFzqmVPZY",
        |  "feeAssetId" : null,
        |  "proofs" : [ "3Te6aX2Zqu4ZHYaZP5P3rwZdEPEfnxSduGdugoLC5DW6jK8mVAJY1w1tf3HUHcSu3jw7ApNL6VdShWWc7KR7hg4w" ],
        |  "price" : 5000000000,
        |  "id" : "5twq9s3Q6k3ANV8cw26TggvweGg4SJ4oesQv6sUdNBG9",
        |  "order2" : {
        |    "senderPublicKey" : "3FfErcfPcSgV4CEeCTjcUo7bHxbt5tJuknLS1AVFDYAW",
        |    "orderType" : "sell",
        |    "amount" : 3,
        |    "signature" : "43oxMXdRxZ2K9eoB58H7EPvU4WY5Vqo3N2GD2oiv8cwCGkToJ7bbJvesRnECdg3pfetWrgyZCa5Uw1U2De8V4jpi",
        |    "assetPair" : {
        |      "amountAsset" : null,
        |      "priceAsset" : "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"
        |    },
        |    "version" : 2,
        |    "matcherFee" : 2,
        |    "sender" : "3DTuUdm2zW9oABg9dcLS6VaYVFSUmEkqgSA",
        |    "price" : 5000000000,
        |    "proofs" : [ "43oxMXdRxZ2K9eoB58H7EPvU4WY5Vqo3N2GD2oiv8cwCGkToJ7bbJvesRnECdg3pfetWrgyZCa5Uw1U2De8V4jpi" ],
        |    "matcherPublicKey" : "9BUoYQYq7K38mkk61q8aMH9kD9fKSVL1Fib7FbH6nUkQ",
        |    "expiration" : 1529584336241,
        |    "id" : "DgViGJqyP7s4dKEZuZ5JV3Pgm6bjKFpNa3YqaHFYiPRG",
        |    "timestamp" : 1526992336241
        |  },
        |  "order1" : {
        |    "senderPublicKey" : "6SKvVVjLaGNBEj98hpyWmZYYreYzGxxVBKwywBBtaEBy",
        |    "orderType" : "buy",
        |    "amount" : 2,
        |    "signature" : "NCQHfaPCRa3mUT3Z1pnL9cA3YuTGpNthyPTej3n8tDpdqiQZ1mHyv8KSQxLWyp3txUDFrVpVRgw8dPQATcgJidk",
        |    "assetPair" : {
        |      "amountAsset" : null,
        |      "priceAsset" : "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"
        |    },
        |    "version" : 2,
        |    "matcherFee" : 1,
        |    "sender" : "3DWfFRmeEkSKFcjhMpUNx2yMkpXnDvyc9HB",
        |    "price" : 6000000000,
        |    "proofs" : [ "NCQHfaPCRa3mUT3Z1pnL9cA3YuTGpNthyPTej3n8tDpdqiQZ1mHyv8KSQxLWyp3txUDFrVpVRgw8dPQATcgJidk" ],
        |    "matcherPublicKey" : "9BUoYQYq7K38mkk61q8aMH9kD9fKSVL1Fib7FbH6nUkQ",
        |    "expiration" : 1529584336241,
        |    "id" : "ztkXA9DGLr3wACqZY4Be46o51J2aqf1g9e6hMb2p3Nx",
        |    "timestamp" : 1526992336241
        |  },
        |  "buyMatcherFee" : 1,
        |  "timestamp" : 1526992336241
        |}
        |
        |""".stripMargin
    )

    val tx = ExchangeTxSerializer.parseBytes(bytes).get
    tx.json() shouldBe json
    assert(crypto.verify(tx.sellOrder.signature, tx.sellOrder.bodyBytes(), tx.sellOrder.sender), "sellOrder signature should be valid")
    assert(crypto.verify(tx.buyOrder.signature, tx.buyOrder.bodyBytes(), tx.buyOrder.sender), "buyOrder signature should be valid")
    assert(crypto.verify(tx.signature, tx.bodyBytes(), tx.sender), "signature should be valid")
  }

  property("ExchangeTransaction invariants validation") {

    forAll(preconditions) { case (sender1, sender2, matcher, pair, buyerMatcherFeeAssetId, sellerMatcherFeeAssetId, versions) =>
      val time                = ntpTime.correctedTime()
      val expirationTimestamp = time + Order.MaxLiveTime / 2

      val buyPrice       = 60 * Order.PriceConstant
      val sellPrice      = 50 * Order.PriceConstant
      val buyAmount      = 2
      val sellAmount     = 3
      val buyMatcherFee  = 1
      val sellMatcherFee = 2

      val (buyV, sellV, exchangeV) = versions

      val buy = Order
        .buy(
          buyV,
          sender1,
          matcher.publicKey,
          pair,
          buyAmount,
          buyPrice,
          time,
          expirationTimestamp,
          buyMatcherFee,
          if (buyV == 3) buyerMatcherFeeAssetId else Dcc
        )
        .explicitGet()
      val sell = Order
        .sell(
          sellV,
          sender2,
          matcher.publicKey,
          pair,
          sellAmount,
          sellPrice,
          time,
          expirationTimestamp,
          sellMatcherFee,
          if (sellV == 3) sellerMatcherFeeAssetId else Dcc
        )
        .explicitGet()

      def create(
          buyOrder: Order = buy,
          sellOrder: Order = sell,
          amount: Long = buyAmount,
          price: Long = sellPrice,
          buyMatcherFee: Long = buyMatcherFee,
          sellMatcherFee: Long = 1,
          fee: Long = 1,
          timestamp: Long = expirationTimestamp - Order.MaxLiveTime,
          version: Byte = exchangeV
      ): Either[ValidationError, ExchangeTransaction] = {
        ExchangeTransaction
          .create(
            order1 = buyOrder,
            order2 = sellOrder,
            amount = amount,
            price = price,
            buyMatcherFee = buyMatcherFee,
            sellMatcherFee = sellMatcherFee,
            fee = fee,
            timestamp = timestamp,
            version = version
          )
      }

      buy.version shouldBe buyV
      sell.version shouldBe sellV

      create() shouldBe an[Right[?, ?]]
      create(fee = pow(10, 18).toLong) shouldBe an[Right[?, ?]]
      create(amount = Order.MaxAmount) shouldBe an[Right[?, ?]]

      create(fee = -1) shouldBe an[Left[?, ?]]
      create(amount = -1) shouldBe an[Left[?, ?]]
      create(amount = Order.MaxAmount + 1) shouldBe an[Left[?, ?]]
      create(price = -1) shouldBe an[Left[?, ?]]
      create(sellMatcherFee = Order.MaxAmount + 1) shouldBe an[Left[?, ?]]
      create(buyMatcherFee = Order.MaxAmount + 1) shouldBe an[Left[?, ?]]
      create(fee = Order.MaxAmount + 1) shouldBe an[Left[?, ?]]

      create(buyOrder = buy.copy(orderType = OrderType.SELL)) shouldBe Left(GenericError("order1 should have OrderType.BUY"))
      create(buyOrder = buy.copy(assetPair = buy.assetPair.copy(amountAsset = sell.assetPair.priceAsset))) shouldBe an[Left[?, ?]]
      create(buyOrder = buy.copy(expiration = 1L)) shouldBe an[Left[?, ?]]
      create(buyOrder = buy.copy(expiration = buy.expiration + 1)) shouldBe an[Left[?, ?]]
      create(buyOrder = buy.copy(matcherPublicKey = sender2.publicKey)) shouldBe an[Left[?, ?]]

      create(sellOrder = sell.copy(orderType = OrderType.BUY)) shouldBe Left(GenericError("sellOrder should has OrderType.SELL"))
      create(sellOrder = sell.copy(assetPair = sell.assetPair.copy(priceAsset = buy.assetPair.amountAsset))) shouldBe an[Left[?, ?]]
      create(sellOrder = sell.copy(expiration = 1L)) shouldBe an[Left[?, ?]]
      create(sellOrder = sell.copy(expiration = sell.expiration + 1)) shouldBe an[Left[?, ?]]
      create(sellOrder = sell.copy(matcherPublicKey = sender2.publicKey)) shouldBe an[Left[?, ?]]

      create(sellOrder = buy, buyOrder = sell) shouldBe Left(GenericError("order1 should have OrderType.BUY"))
      create(version = TxVersion.V3, sellOrder = buy, buyOrder = sell) shouldBe an[Right[?, ?]]
      create(version = TxVersion.V3, sellOrder = sell, buyOrder = sell) shouldBe Left(GenericError("buyOrder should has OrderType.BUY"))
      create(version = TxVersion.V3, sellOrder = buy, buyOrder = buy) shouldBe Left(GenericError("sellOrder should has OrderType.SELL"))

      create(
        buyOrder = buy.copy(assetPair = buy.assetPair.copy(amountAsset = Dcc)),
        sellOrder = sell.copy(assetPair = sell.assetPair.copy(priceAsset = IssuedAsset(ByteStr(Array(1: Byte)))))
      ) shouldBe an[Left[?, ?]]
    }
  }

  def createExTx(buy: Order, sell: Order, price: Long, version: TxVersion): Either[ValidationError, ExchangeTransaction] = {
    val matcherFee = 300000L
    val amount     = math.min(buy.amount.value, sell.amount.value)

    ExchangeTransaction.create(
      order1 = buy,
      order2 = sell,
      amount = amount,
      price = price,
      buyMatcherFee = (BigInt(matcherFee) * amount / buy.amount.value).toLong,
      sellMatcherFee = (BigInt(matcherFee) * amount / sell.amount.value).toLong,
      fee = matcherFee,
      timestamp = ntpTime.correctedTime(),
      version = version
    )
  }

  property("Test transaction with small amount and expired order") {

    forAll(preconditions) { case (sender1, sender2, matcher, pair, buyerMatcherFeeAssetId, sellerMatcherFeeAssetId, versions) =>
      val time                     = ntpTime.correctedTime()
      val expirationTimestamp      = time + Order.MaxLiveTime / 2
      val buyPrice                 = 1 * Order.PriceConstant
      val sellPrice                = (0.50 * Order.PriceConstant).toLong
      val matcherFee               = 300000L
      val (sellV, buyV, exchangeV) = versions

      val sell =
        Order
          .sell(
            sellV,
            sender2,
            matcher.publicKey,
            pair,
            2,
            sellPrice,
            time,
            expirationTimestamp,
            matcherFee,
            if (sellV == 3) sellerMatcherFeeAssetId else Dcc
          )
          .explicitGet()
      val buy =
        Order
          .buy(
            buyV,
            sender1,
            matcher.publicKey,
            pair,
            1,
            buyPrice,
            time,
            expirationTimestamp,
            matcherFee,
            if (buyV == 3) buyerMatcherFeeAssetId else Dcc
          )
          .explicitGet()

      createExTx(buy, sell, sellPrice, exchangeV) shouldBe an[Right[?, ?]]

      val sell1 =
        if (sellV == 3) {
          Order.sell(sellV, sender2, matcher.publicKey, pair, 1, buyPrice, time, time - 1, matcherFee, sellerMatcherFeeAssetId).explicitGet()
        } else Order.sell(sellV, sender2, matcher.publicKey, pair, 1, buyPrice, time, time - 1, matcherFee).explicitGet()

      createExTx(buy, sell1, buyPrice, exchangeV) shouldBe Left(OrderValidationError(sell1, "expiration should be > currentTime"))
    }
  }

  property("JSON format validation") {
    val js = Json.parse("""{
         "version": 1,
         "type":7,
         "id":"FaDrdKax2KBZY6Mh7K3tWmanEdzZx6MhYUmpjV3LBJRp",
         "sender":"3DZvoXBfpyLdcXubQQTJJoCvAYyH8CuYtCF",
         "senderPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
         "fee":1,
         "feeAssetId": null,
         "timestamp":1526992336241,
         "signature":"5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa",
         "proofs":["5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa"],
         "order1":{
            "version": 1,
            "id":"EdUTcUZNK3NYKuPrsPCkZGzVUwpjx6qVjd4TgBwna7po",
            "sender":"3DSc629P9NjvBDTgh1oTMUwP8kR7hrmgxqr",
            "senderPublicKey":"BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "signature":"2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs",
            "proofs":["2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs"]
         },
         "order2":{
            "version": 1,
            "id":"DS9HPBGRMJcquTb3sAGAJzi73jjMnFFSWWHfzzKK32Q7",
            "sender":"3DRr4eiD8QQUhXv1FQPpBDosfuwbBfSfHB7",
            "senderPublicKey":"7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq",
            "proofs":["2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    val buy = Order(
      Order.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ").explicitGet(),
        Proofs(ByteStr.decodeBase58("2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("DCC", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.BUY,
      TxExchangeAmount.unsafeFrom(2),
      TxOrderPrice.unsafeFrom(6000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(1)
    )

    val sell = Order(
      Order.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV").explicitGet(),
        Proofs(ByteStr.decodeBase58("2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("DCC", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.SELL,
      TxExchangeAmount.unsafeFrom(3),
      TxOrderPrice.unsafeFrom(5000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(2)
    )

    val tx = ExchangeTransaction
      .create(
        TxVersion.V1,
        buy,
        sell,
        2,
        5000000000L,
        1,
        1,
        1,
        1526992336241L,
        Proofs(ByteStr.decodeBase58("5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa").get)
      )
      .explicitGet()

    js should matchJson(tx.json())
  }

  property("JSON format validation V2") {
    val js = Json.parse("""{
         "version": 2,
         "type":7,
         "id":"5KUDbPKjAoNHTMyae9zJZpFjYFAbeSQMQ9rzgkDEEUx6",
         "sender":"3DZvoXBfpyLdcXubQQTJJoCvAYyH8CuYtCF",
         "senderPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
         "fee":1,
         "feeAssetId": null,
         "timestamp":1526992336241,
         "proofs":["5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa"],
         "order1":{
            "version": 2,
            "id":"EcndU4vU3SJ58KZAXJPKACvMhijTzgRjLTsuWxSWaQUK",
            "sender":"3DSc629P9NjvBDTgh1oTMUwP8kR7hrmgxqr",
            "senderPublicKey":"BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "signature":"2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs",
            "proofs":["2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs"]
         },
         "order2":{
            "version": 1,
            "id":"DS9HPBGRMJcquTb3sAGAJzi73jjMnFFSWWHfzzKK32Q7",
            "sender":"3DRr4eiD8QQUhXv1FQPpBDosfuwbBfSfHB7",
            "senderPublicKey":"7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq",
            "proofs":["2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    val buy = Order(
      Order.V2,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ").explicitGet(),
        Proofs(ByteStr.decodeBase58("2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("DCC", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.BUY,
      TxExchangeAmount.unsafeFrom(2),
      TxOrderPrice.unsafeFrom(6000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(1)
    )

    val sell = Order(
      Order.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV").explicitGet(),
        Proofs(ByteStr.decodeBase58("2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("DCC", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.SELL,
      TxExchangeAmount.unsafeFrom(3),
      TxOrderPrice.unsafeFrom(5000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(2)
    )

    val tx = ExchangeTransaction
      .create(
        TxVersion.V2,
        buy,
        sell,
        2,
        5000000000L,
        1,
        1,
        1,
        1526992336241L,
        Proofs(Seq(ByteStr.decodeBase58("5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa").get))
      )
      .explicitGet()

    js should matchJson(tx.json())
  }

  property("JSON format validation V2 OrderV3") {
    val js = Json.parse("""{
         "version": 2,
         "type":7,
         "id":"3G1U1UX2mtWXVdZTZNjEYvPeNn6cyYmmjHYUePrg4zM5",
         "sender":"3DZvoXBfpyLdcXubQQTJJoCvAYyH8CuYtCF",
         "senderPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
         "fee":1,
         "feeAssetId": null,
         "timestamp":1526992336241,
         "proofs":["5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa"],
         "order1":{
            "version": 3,
            "id":"8KZby2jXfFCaFtEKejqBbutQvyimgeQykwPKGi3ufNiA",
            "sender":"3DSc629P9NjvBDTgh1oTMUwP8kR7hrmgxqr",
            "senderPublicKey":"BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "matcherFeeAssetId":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy",
            "signature":"2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs",
            "proofs":["2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs"]
         },
         "order2":{
            "version": 1,
            "id":"DS9HPBGRMJcquTb3sAGAJzi73jjMnFFSWWHfzzKK32Q7",
            "sender":"3DRr4eiD8QQUhXv1FQPpBDosfuwbBfSfHB7",
            "senderPublicKey":"7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq",
            "proofs":["2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    val buy = Order(
      Order.V3,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ").explicitGet(),
        Proofs(ByteStr.decodeBase58("2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("DCC", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.BUY,
      TxExchangeAmount.unsafeFrom(2),
      TxOrderPrice.unsafeFrom(6000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(1),
      extractAssetId("9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get
    )

    val sell = Order(
      Order.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV").explicitGet(),
        Proofs(ByteStr.decodeBase58("2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("DCC", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.SELL,
      TxExchangeAmount.unsafeFrom(3),
      TxOrderPrice.unsafeFrom(5000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(2)
    )

    val tx = ExchangeTransaction
      .create(
        TxVersion.V2,
        buy,
        sell,
        2,
        5000000000L,
        1,
        1,
        1,
        1526992336241L,
        Proofs(Seq(ByteStr.decodeBase58("5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa").get))
      )
      .explicitGet()

    js should matchJson(tx.json())
  }
}
