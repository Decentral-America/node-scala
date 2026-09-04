package com.decentralchain.transaction

import com.decentralchain.crypto.bls.{BlsKeyPair, BlsUtils}
import com.decentralchain.state.Height
import com.decentralchain.test.FlatSpec

/** The PoP message is now built in exactly ONE place (audit M2 fix + de-duplication of the three
  * hand-rolled copies that used to live in `mkPopSignature`, `CommitToGenerationTransactionDiff`,
  * and `BlockDiffer.validateCommitmentsOnSnapshotPath`).
  */
class CommitToGenerationPopMessageSpec extends FlatSpec {
  private val sender     = TxHelpers.signer(7)
  private val endorserKp = BlsKeyPair(TxHelpers.signer(8).privateKey)
  private val start      = Height(3000)
  private val chainId    = 'T'.toByte

  "popMessage" should "bind chainId and sender (audit M2)" in {
    val msg = CommitToGenerationTransaction.popMessage(chainId, sender.publicKey, endorserKp.publicKey, start)
    msg shouldBe (Array(chainId) ++ sender.publicKey.arr ++ endorserKp.publicKey.arr ++ start.toByteArray)
    msg.length shouldBe 85
  }

  it should "differ across chain ids and across senders" in {
    def msg(cid: Byte, s: com.decentralchain.account.PublicKey) =
      CommitToGenerationTransaction.popMessage(cid, s, endorserKp.publicKey, start).toSeq

    msg('T'.toByte, sender.publicKey) should not be msg('W'.toByte, sender.publicKey)
    msg('T'.toByte, sender.publicKey) should not be msg('T'.toByte, TxHelpers.signer(9).publicKey)
  }

  "PopDst" should "be the POP domain-separation tag" in {
    CommitToGenerationTransaction.PopDst shouldBe BlsUtils.BlsPopDomainSeparationTag
  }

  "mkPopSignature" should "verify under PopDst/popMessage and fail under a different domain" in {
    val signature = CommitToGenerationTransaction.mkPopSignature(endorserKp, start, sender.publicKey, chainId)
    val message   = CommitToGenerationTransaction.popMessage(chainId, sender.publicKey, endorserKp.publicKey, start)

    endorserKp.publicKey.verify(message, signature, CommitToGenerationTransaction.PopDst) shouldBe true
    endorserKp.publicKey.verify(message, signature, BlsUtils.BlsEndorseDomainSeparationTag) shouldBe false
  }
}
