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

/** Audit M2: the PoP a CommitToGenerationTransaction carries binds BOTH the chain it was minted for
  * and the account registering it -- not just the raw `endorserPk` bytes. These specs pin that
  * property directly against `CommitToGenerationTransactionDiff`, the on-chain (full-node)
  * verification site. `LightNodeSnapshotPathPopSpec` mirrors the same property against the
  * light-node snapshot path (`BlockDiffer.validateCommitmentsOnSnapshotPath`) -- both sites must be
  * verified, or guarding only one just moves the attack.
  */
class CommitToGenerationPopV2Spec extends FreeSpec with WithDomain {
  private val generationPeriodLength = 8
  private val activationHeight       = Height(3)

  private def settings =
    DeterministicFinality
      .configure(_.copy(generationPeriodLength = generationPeriodLength))
      .setFeaturesHeight(BlockchainFeatures.DeterministicFinality -> activationHeight.toInt)

  private val sender = TxHelpers.defaultSigner

  "a correctly-signed PoP is accepted" in {
    withDomain(settings, AddrWithBalance.enoughBalances(sender)) { d =>
      while (d.blockchain.height < 3) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
      val tx          = TxHelpers.commitToGeneration(periodStart, sender)

      d.appendBlockE(tx) should beRight
    }
  }

  "M2-a cross-chain: a PoP minted for one chainId is not valid on another" in {
    val otherChainId = 'T'.toByte
    val thisChainId  = AddressScheme.current.chainId
    thisChainId should not be otherChainId

    withDomain(settings, AddrWithBalance.enoughBalances(sender)) { d =>
      while (d.blockchain.height < 3) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
      val endorserKp  = BlsKeyPair(sender.privateKey)

      // PoP minted for `otherChainId`, transaction built for this node's chainId -- rejected.
      val crossChainSig = CommitToGenerationTransaction.mkPopSignature(endorserKp, periodStart, sender.publicKey, otherChainId)
      val crossChainTx  = TxHelpers
        .commitToGeneration(periodStart, sender)
        .copy(commitmentSignature = crossChainSig)
      val resigned =
        crossChainTx.copy(proofs = com.decentralchain.transaction.Proofs(com.decentralchain.crypto.sign(sender.privateKey, crossChainTx.bodyBytes())))
      d.appendBlockE(resigned) should produce("Invalid commitment signature")
    }
  }

  "M2-b mempool lift: a PoP minted for sender A is not valid resubmitted under sender B" in {
    withDomain(settings, AddrWithBalance.enoughBalances(sender, TxHelpers.secondSigner)) { d =>
      val senderB = TxHelpers.secondSigner
      while (d.blockchain.height < 3) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
      val endorserKp  = BlsKeyPair(sender.privateKey)
      val chainId     = AddressScheme.current.chainId

      // PoP minted binding sender A's pubkey, transaction submitted with sender = B -- rejected.
      val liftedSig = CommitToGenerationTransaction.mkPopSignature(endorserKp, periodStart, sender.publicKey, chainId)
      val liftedTx  = CommitToGenerationTransaction
        .selfSigned(
          TxVersion.V1,
          senderB,
          endorserKp.publicKey,
          periodStart,
          TxHelpers.timestamp,
          TestValues.commitToGenerationFee,
          liftedSig,
          chainId
        )
        .explicitGet()
      d.appendBlockE(liftedTx) should produce("Invalid commitment signature")
    }
  }

  "cross-context transplant fails BY DOMAIN, not length: a PoP does not verify as an endorsement" in {
    val periodStart = Height(3001)
    val endorserKp  = BlsKeyPair(sender.privateKey)
    val chainId     = AddressScheme.current.chainId

    val popMessage = CommitToGenerationTransaction.popMessage(chainId, sender.publicKey, endorserKp.publicKey, periodStart)
    val popSig     = endorserKp.sign(popMessage, BlsUtils.BlsPopDomainSeparationTag)

    // Same bytes (message AND signature), verified under the ENDORSE domain instead of POP: must
    // fail. Asserted on the identical byte array so a length mismatch cannot be the discriminator --
    // the POP and ENDORSE messages are not even the same shape, but this assertion doesn't rely on
    // that; it proves the DST alone is load-bearing.
    BlsUtils.verifyBasic(popSig.arr, popMessage, endorserKp.publicKey.arr, BlsUtils.BlsEndorseDomainSeparationTag) shouldBe a[Left[?, ?]]
    // Sanity: the same bytes DO verify under the correct (POP) domain.
    BlsUtils.verifyBasic(popSig.arr, popMessage, endorserKp.publicKey.arr, BlsUtils.BlsPopDomainSeparationTag) should beRight
  }
}
