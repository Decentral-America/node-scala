package com.decentralchain.state.diffs

import com.decentralchain.account.AddressScheme
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsUtils}
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.state.Height
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.{DeterministicFinality, DCCSettingsOps}
import com.decentralchain.TestValues
import com.decentralchain.transaction.{CommitToGenerationTransaction, TxHelpers, TxVersion}

/** Audit M2: the PoP a CommitToGenerationTransaction carries must, post-activation of feature 30
  * (`BlockchainFeatures.BlsCryptoV2`), bind BOTH the chain it was minted for and the account
  * registering it -- not just the raw `endorserPk ‖ periodStart` bytes the legacy layout covers.
  * These specs pin that property directly against `CommitToGenerationTransactionDiff`, the on-chain
  * (full-node) verification site. `BlsCryptoV2SnapshotPathPopSpec` mirrors cases 1-2 against the
  * light-node snapshot path (`BlockDiffer.validateCommitmentsOnSnapshotPath`) -- both sites must be
  * gated, or guarding only one just moves the attack.
  */
class CommitToGenerationPopV2Spec extends FreeSpec with WithDomain {
  private val generationPeriodLength = 8
  private val activationHeight       = Height(3)

  private def settingsWithBlsV2At(h: Int) =
    DeterministicFinality
      .configure(_.copy(generationPeriodLength = generationPeriodLength))
      .setFeaturesHeight(BlockchainFeatures.DeterministicFinality -> activationHeight.toInt, BlockchainFeatures.BlsCryptoV2 -> h)

  private val sender = TxHelpers.defaultSigner

  "activation boundary" - {
    "H-1 (pre-activation): legacy PoP accepted, v2 PoP rejected" in {
      val h = 5
      withDomain(settingsWithBlsV2At(h), AddrWithBalance.enoughBalances(sender)) { d =>
        // The commitment tx is validated as part of the NEXT appended block, so the chain must sit
        // one block below h-1 for that next block to land at height h-1 (pre-activation).
        while (d.blockchain.height < h - 2) d.appendBlock()
        d.blockchain.height shouldBe h - 2

        val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
        val legacyTx     = TxHelpers.commitToGeneration(periodStart, sender, cryptoV2 = false)
        val v2Tx          = TxHelpers.commitToGeneration(periodStart, sender, cryptoV2 = true)

        d.appendBlockE(v2Tx) should produce("Invalid commitment signature")
        d.appendBlockE(legacyTx) should beRight
      }
    }

    "H and H+1 (post-activation): v2 PoP accepted, legacy PoP rejected" in {
      val h = 5
      Seq(h, h + 1).foreach { targetHeight =>
        withDomain(settingsWithBlsV2At(h), AddrWithBalance.enoughBalances(sender)) { d =>
          // Same off-by-one as above: sit one block below targetHeight so the tx-carrying block lands
          // exactly at targetHeight.
          while (d.blockchain.height < targetHeight - 1) d.appendBlock()
          d.blockchain.height shouldBe targetHeight - 1

          val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
          val legacyTx     = TxHelpers.commitToGeneration(periodStart, sender, cryptoV2 = false)
          val v2Tx          = TxHelpers.commitToGeneration(periodStart, sender, cryptoV2 = true)

          d.appendBlockE(legacyTx) should produce("Invalid commitment signature")
          d.appendBlockE(v2Tx) should beRight
        }
      }
    }
  }

  "M2-a cross-chain: a PoP minted for one chainId is not valid on another" - {
    val h            = 5
    val otherChainId = 'T'.toByte
    val thisChainId  = AddressScheme.current.chainId
    thisChainId should not be otherChainId

    "v2, post-activation: rejected" in withDomain(settingsWithBlsV2At(h), AddrWithBalance.enoughBalances(sender)) { d =>
      // Tx-carrying block lands at height h (post-activation), so sit one block below it first.
      while (d.blockchain.height < h - 1) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
      val endorserKp  = BlsKeyPair(sender.privateKey)

      // v2 PoP minted for `otherChainId`, transaction built for this node's chainId -- rejected.
      val crossChainV2Sig = CommitToGenerationTransaction.mkPopSignature(endorserKp, periodStart, sender.publicKey, otherChainId, cryptoV2 = true)
      val crossChainV2Tx  = TxHelpers
        .commitToGeneration(periodStart, sender, cryptoV2 = true)
        .copy(commitmentSignature = crossChainV2Sig)
      val resignedV2 = crossChainV2Tx.copy(proofs =
        com.decentralchain.transaction.Proofs(com.decentralchain.crypto.sign(sender.privateKey, crossChainV2Tx.bodyBytes()))
      )
      d.appendBlockE(resignedV2) should produce("Invalid commitment signature")
    }

    // Under the legacy (pre-activation) path the same construction is accepted, because the legacy
    // PoP never covered chainId -- this pins the finding so the v2 fix stays load-bearing (without
    // it, this whole test would be a no-op: v2 wouldn't be reachable at all).
    "legacy, pre-activation: accepted" in withDomain(settingsWithBlsV2At(h), AddrWithBalance.enoughBalances(sender)) { d =>
      // Tx-carrying block lands at height h-1 (pre-activation).
      while (d.blockchain.height < h - 2) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
      val endorserKp  = BlsKeyPair(sender.privateKey)

      val crossChainLegacySig =
        CommitToGenerationTransaction.mkPopSignature(endorserKp, periodStart, sender.publicKey, otherChainId, cryptoV2 = false)
      val crossChainLegacyTx = TxHelpers
        .commitToGeneration(periodStart, sender, cryptoV2 = false)
        .copy(commitmentSignature = crossChainLegacySig)
      val resignedLegacy = crossChainLegacyTx.copy(proofs =
        com.decentralchain.transaction.Proofs(com.decentralchain.crypto.sign(sender.privateKey, crossChainLegacyTx.bodyBytes()))
      )
      d.appendBlockE(resignedLegacy) should beRight
    }
  }

  "M2-b mempool lift: a v2 PoP minted for sender A is not valid resubmitted under sender B" - {
    val h = 5

    "v2, post-activation: rejected" in withDomain(settingsWithBlsV2At(h), AddrWithBalance.enoughBalances(sender, TxHelpers.secondSigner)) { d =>
      val senderB = TxHelpers.secondSigner
      // Tx-carrying block lands at height h (post-activation), so sit one block below it first.
      while (d.blockchain.height < h - 1) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
      val endorserKp  = BlsKeyPair(sender.privateKey)
      val chainId     = AddressScheme.current.chainId

      // v2 PoP minted binding sender A's pubkey, transaction submitted with sender = B -- rejected.
      val liftedV2Sig = CommitToGenerationTransaction.mkPopSignature(endorserKp, periodStart, sender.publicKey, chainId, cryptoV2 = true)
      val liftedV2Tx  = CommitToGenerationTransaction
        .selfSigned(TxVersion.V1, senderB, endorserKp.publicKey, periodStart, TxHelpers.timestamp, TestValues.commitToGenerationFee, liftedV2Sig, chainId)
        .explicitGet()
      d.appendBlockE(liftedV2Tx) should produce("Invalid commitment signature")
    }

    // Under the legacy (pre-activation) path the same lift succeeds, because the legacy PoP never
    // covered the registering sender -- this pins the finding so the v2 fix stays load-bearing.
    "legacy, pre-activation: accepted" in withDomain(settingsWithBlsV2At(h), AddrWithBalance.enoughBalances(sender, TxHelpers.secondSigner)) { d =>
      val senderB = TxHelpers.secondSigner
      // Tx-carrying block lands at height h-1 (pre-activation).
      while (d.blockchain.height < h - 2) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
      val endorserKp  = BlsKeyPair(sender.privateKey)
      val chainId     = AddressScheme.current.chainId

      val liftedLegacySig = CommitToGenerationTransaction.mkPopSignature(endorserKp, periodStart, sender.publicKey, chainId, cryptoV2 = false)
      val liftedLegacyTx  = CommitToGenerationTransaction
        .selfSigned(
          TxVersion.V1,
          senderB,
          endorserKp.publicKey,
          periodStart,
          TxHelpers.timestamp,
          TestValues.commitToGenerationFee,
          liftedLegacySig,
          chainId
        )
        .explicitGet()
      d.appendBlockE(liftedLegacyTx) should beRight
    }
  }

  "cross-context transplant fails BY DOMAIN, not length: a v2 PoP does not verify as a v2 endorsement" in {
    val periodStart = Height(3001)
    val endorserKp   = BlsKeyPair(sender.privateKey)
    val chainId      = AddressScheme.current.chainId

    val popMessage = CommitToGenerationTransaction.popMessage(chainId, sender.publicKey, endorserKp.publicKey, periodStart, cryptoV2 = true)
    val popSig     = endorserKp.sign(popMessage, BlsUtils.BlsPopDomainSeparationTagV2)

    // Same bytes (message AND signature), verified under the ENDORSE domain instead of POP: must
    // fail. Asserted on the identical byte array so a length mismatch cannot be the discriminator --
    // the POP and ENDORSE v2 messages are not even the same shape, but this assertion doesn't rely on
    // that; it proves the DST alone is load-bearing.
    BlsUtils.verifyBasic(popSig.arr, popMessage, endorserKp.publicKey.arr, BlsUtils.BlsEndorseDomainSeparationTagV2) shouldBe a[Left[?, ?]]
    // Sanity: the same bytes DO verify under the correct (POP) domain.
    BlsUtils.verifyBasic(popSig.arr, popMessage, endorserKp.publicKey.arr, BlsUtils.BlsPopDomainSeparationTagV2) should beRight
  }
}
