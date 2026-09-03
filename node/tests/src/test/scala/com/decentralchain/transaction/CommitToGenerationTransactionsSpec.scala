package com.decentralchain.transaction

import com.decentralchain.account.{AddressScheme, KeyPair, PrivateKey, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.consensus.GeneratingBalanceProvider
import com.decentralchain.crypto
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey, BlsSignature, TestBlsKeyPair}
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.state.Height
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.{DeterministicFinality, DCCSettingsOps}
import com.decentralchain.transaction.serialization.impl.PBTransactionSerializer

import scala.util.{Failure, Success}

class CommitToGenerationTransactionsSpec extends FreeSpec with WithDomain {
  private val origTx = CommitToGenerationTransaction(
    version = TxVersion.V1,
    sender = PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
    endorserPublicKey = BlsPublicKey(Base58.decode("6CagLT3FjEcaNHPYCaG2dcfEfzDj6ynVeZbxbLHkHdfzvbfBmBMkkatTYcBXD9cHMU")).explicitGet(),
    generationPeriodStart = Height(3000),
    timestamp = 1526287561757L,
    fee = TxPositiveAmount.unsafeFrom(100000000),
    commitmentSignature = BlsSignature(
      Base58.decode(
        "oJUBPLXnqejpwkkifzBbyQp63mPwypYq9GV7eAYqQGAvsE2LxU6csrrwLWgK1HdW28Ygku7vfkcMW1TCDCFymVXoqi7SpCwWGp3P6gegHusSPBsuVQQiQ5BWTYpUpSJjiBL"
      )
    ).explicitGet(),
    proofs = Proofs(ByteStr.decodeBase58("28kE1uN1pX2bwhzr9UHw5UuB9meTFEDFgeunNgy6nZWpHX4pzkGYotu8DhQ88AdqUG6Yy5wcXgHseKPBUygSgRMJ").get),
    chainId = AddressScheme.current.chainId
  )

  "JSON parsing" in {
    val json = origTx.json()
    (json \ "type").as[Int] shouldBe 19
    (json \ "version").as[Int] shouldBe 1
    (json \ "fee").as[Long] shouldBe 100000000L
    (json \ "feeAssetId").asOpt[String] shouldBe None
    (json \ "timestamp").as[Long] shouldBe 1526287561757L
    (json \ "sender").as[String] shouldBe origTx.sender.toAddress.toString
    (json \ "senderPublicKey").as[String] shouldBe "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z"
    (json \ "generationPeriodStart").as[Int] shouldBe 3000
    (json \ "endorserPublicKey").as[String] shouldBe "6CagLT3FjEcaNHPYCaG2dcfEfzDj6ynVeZbxbLHkHdfzvbfBmBMkkatTYcBXD9cHMU"
    (json \ "commitmentSignature").as[
      String
    ] shouldBe "oJUBPLXnqejpwkkifzBbyQp63mPwypYq9GV7eAYqQGAvsE2LxU6csrrwLWgK1HdW28Ygku7vfkcMW1TCDCFymVXoqi7SpCwWGp3P6gegHusSPBsuVQQiQ5BWTYpUpSJjiBL"
    (json \ "proofs").as[Seq[String]] shouldBe Seq("28kE1uN1pX2bwhzr9UHw5UuB9meTFEDFgeunNgy6nZWpHX4pzkGYotu8DhQ88AdqUG6Yy5wcXgHseKPBUygSgRMJ")
    (json \ "chainId").as[Int] shouldBe AddressScheme.current.chainId.toInt
    (json \ "id").asOpt[String].isDefined shouldBe true
  }

  "PB roundtrip" in {
    PBTransactionSerializer.parseBytes(PBTransactionSerializer.bytes(origTx)) match {
      case Success(tx: CommitToGenerationTransaction) =>
        tx shouldBe origTx
        tx.proofs shouldBe origTx.proofs
      case Success(tx)        => fail(s"Unexpected transaction type: ${tx.tpe.transactionName}")
      case Failure(exception) => fail(exception)
    }
  }

  private val sender                 = TxHelpers.defaultSigner
  private val generationPeriodLength = 8
  private val defaultSettings        = DeterministicFinality.configure(_.copy(generationPeriodLength = generationPeriodLength))

  "Accepted on the feature activation height, first period starts at activation_height+generation_period+1" in {
    val activationHeight = Height(3)
    withDomain(
      defaultSettings.setFeaturesHeight(BlockchainFeatures.DeterministicFinality -> activationHeight.toInt),
      AddrWithBalance.enoughBalances(sender)
    ) { d =>
      val tx = TxHelpers.commitToGeneration(activationHeight + generationPeriodLength + 1, sender)
      d.appendBlockE(tx) should produce("Deterministic Finality & RIDE V9 feature has not been activated yet")
      d.appendBlock()
      d.appendBlock(tx)
    }
  }

  "Generator deposit taken and returned" in withDomain(
    DeterministicFinality.configure(x => x.copy(generationPeriodLength = 2)), // Periods in test: [3, 4], [5, 6], [7, 8]
    AddrWithBalance.enoughBalances(sender)
  ) { d =>
    log.info("No deposits")
    d.blockchain.dccPortfolio(sender.toAddress).generationDeposit shouldBe 0L

    log.info("Deposit for one next period")
    val currPeriodTx = TxHelpers.commitToGeneration(Height(3), sender)
    d.appendBlock(currPeriodTx)
    d.blockchain.height shouldBe 2
    d.blockchain.dccPortfolio(sender.toAddress).generationDeposit shouldBe CommitToGenerationTransaction.DepositInDcclets

    log.info("Deposit for one current period")
    d.appendBlock()
    d.blockchain.height shouldBe 3
    d.blockchain.dccPortfolio(sender.toAddress).generationDeposit shouldBe CommitToGenerationTransaction.DepositInDcclets

    log.info("Deposit for two periods")
    val nextPeriodTx = TxHelpers.commitToGeneration(Height(5), sender)
    d.appendBlock(nextPeriodTx)
    val dccPortfolio = d.blockchain.dccPortfolio(sender.toAddress)
    dccPortfolio.generationDeposit shouldBe 2 * CommitToGenerationTransaction.DepositInDcclets
    dccPortfolio.spendableBalance shouldBe (dccPortfolio.balance - dccPortfolio.generationDeposit)

    d.appendBlock()
    d.blockchain.height shouldBe 5

    log.info("Deposit for one period if not committed for next")
    d.blockchain.dccPortfolio(sender.toAddress).generationDeposit shouldBe CommitToGenerationTransaction.DepositInDcclets
  }

  "Can't commit" - {
    "zero public key" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      log.info("First")
      // TestBlsKeyPair.zero() bypasses BlsUtils.mkBlsSecretKey's own seed guard (audit M3) by
      // construction, so this keeps exercising the on-chain rejection (PoP verify / `.validated`)
      // as its own independent defense, regardless of that guard.
      val zeroBlsKp = TestBlsKeyPair.zero()
      val txn       = TxHelpers.commitToGenerationWithEndorserKey(Height(3001), zeroBlsKp, sender)
      d.appendBlockE(txn) should produce("Invalid commitment signature")
    }

    "twice" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender)) { d =>
      log.info("First")
      d.appendBlock(TxHelpers.commitToGeneration(Height(3001), sender))

      log.info("Second")
      d.appendBlockE(TxHelpers.commitToGeneration(Height(3001), sender)) should produce("is already committed")
    }

    "public BLS key twice" in withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(sender, TxHelpers.secondSigner)) { d =>
      def mkTx(sender: KeyPair, blsKp: BlsKeyPair): CommitToGenerationTransaction = {
        val baseTx  = TxHelpers.commitToGeneration(Height(3001), sender)
        val withPop = baseTx.copy(
          endorserPublicKey = blsKp.publicKey,
          commitmentSignature = CommitToGenerationTransaction.mkPopSignature(blsKp, baseTx.generationPeriodStart, baseTx.sender, baseTx.chainId)
        )

        withPop.copy(proofs = Proofs(crypto.sign(sender.privateKey, withPop.bodyBytes())))
      }

      log.debug("First")
      val blsKP = BlsKeyPair(sender.privateKey)
      d.appendBlock(mkTx(sender, blsKP))

      log.debug("Second")
      d.appendBlockE(mkTx(TxHelpers.secondSigner, blsKP)) should produce("is already committed, try another key")
    }

    "with insufficient balance" in {
      val newGenerator = TxHelpers.signer(1005)
      withDomain(
        // GeneratingBalanceProvider.minMiningBalance (Task 20) is feature-gated on
        // SmallerMinimalGeneratingBalance -- without it, the threshold is the larger
        // MinimalEffectiveBalanceForGenerator1, not Generator2 as this test expects below.
        DeterministicFinality.addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance),
        Seq(
          AddrWithBalance(sender.toAddress, 1000000.dcc),
          AddrWithBalance(
            newGenerator.toAddress,
            GeneratingBalanceProvider.MinimalEffectiveBalanceForGenerator2 + CommitToGenerationTransaction.DepositInDcclets
          )
        )
      ) { d =>
        val tx = TxHelpers.commitToGeneration(Height(3001), newGenerator)

        d.appendBlockE(tx) should produce(
          s"Generating balance ${GeneratingBalanceProvider.MinimalEffectiveBalanceForGenerator2 - tx.fee.value} is less than 100000000000 required for block generation"
        )
      }
    }

    "with invalid commitment signature" in {
      val newGenerator     = TxHelpers.signer(1006)
      val otherGeneratorKp = BlsKeyPair(TxHelpers.signer(1007).privateKey)
      withDomain(
        DeterministicFinality,
        Seq(
          AddrWithBalance(sender.toAddress, 1000000.dcc),
          AddrWithBalance(newGenerator.toAddress, 10000.dcc)
        )
      ) { d =>
        val periodStart = Height(3001)
        val unsignedTx  = TxHelpers
          .commitToGeneration(periodStart, newGenerator)
          .copy(commitmentSignature =
            CommitToGenerationTransaction
              .mkPopSignature(otherGeneratorKp, periodStart, newGenerator.publicKey, AddressScheme.current.chainId)
          )
        val signedTx = unsignedTx.copy(proofs = Proofs(crypto.sign(newGenerator.privateKey, unsignedTx.bodyBytes())))

        d.appendBlockE(unsignedTx) should produce("Proof doesn't validate as signature")
        d.appendBlockE(signedTx) should produce("Invalid commitment signature")
      }
    }
  }

  "Expected BLS key and PoP" in {
    val dccPk = PrivateKey(ByteStr.decodeBase58("7UR2CZi6Gv6v1yqmgcPDD98ZtosvtHnNZRxvrHA2Tuyn").get)

    val blsKp = BlsKeyPair(dccPk)
    blsKp.publicKey.byteStr.base64Raw shouldBe "jrugi0W0es2WxuHoptQtchqwactZsldOGucYObZrEIOpxbWmhL8dodvpnzA+2qUf"

    CommitToGenerationTransaction
      .mkPopSignature(blsKp, Height(1001), origTx.sender, AddressScheme.current.chainId)
      .byteStr
      .base64Raw shouldBe
      "mLmqbXwT4ONFAEuZKmSh1O157eKmFoJP6HeWgaFMVcXUNztKrvohPDN5SxvQ552mAaLAy/tWiD6ICqGZ97BSrBYePaKAYcvukoH6bKRxEDVtAk/daGrn2/9+2PJsPGf2"
  }
}
