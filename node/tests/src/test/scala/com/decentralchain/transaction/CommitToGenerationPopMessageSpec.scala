package com.decentralchain.transaction

import com.decentralchain.crypto.bls.BlsKeyPair
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

  "popMessage" should "reproduce the legacy layout byte-for-byte when cryptoV2 = false" in {
    val legacy = CommitToGenerationTransaction.popMessage(chainId, sender.publicKey, endorserKp.publicKey, start, cryptoV2 = false)
    legacy shouldBe (endorserKp.publicKey.arr ++ start.toByteArray)
    legacy.length shouldBe 52
  }

  it should "bind chainId and sender when cryptoV2 = true (audit M2)" in {
    val v2 = CommitToGenerationTransaction.popMessage(chainId, sender.publicKey, endorserKp.publicKey, start, cryptoV2 = true)
    v2 shouldBe (Array(chainId) ++ sender.publicKey.arr ++ endorserKp.publicKey.arr ++ start.toByteArray)
    v2.length shouldBe 85
  }

  it should "differ across chain ids and across senders under v2, and NOT under legacy" in {
    def v2(cid: Byte, s: com.decentralchain.account.PublicKey) =
      CommitToGenerationTransaction.popMessage(cid, s, endorserKp.publicKey, start, cryptoV2 = true).toSeq
    def legacy(cid: Byte, s: com.decentralchain.account.PublicKey) =
      CommitToGenerationTransaction.popMessage(cid, s, endorserKp.publicKey, start, cryptoV2 = false).toSeq

    v2('T'.toByte, sender.publicKey) should not be v2('W'.toByte, sender.publicKey)
    v2('T'.toByte, sender.publicKey) should not be v2('T'.toByte, TxHelpers.signer(9).publicKey)
    // This equality IS the M2 finding, pinned so the legacy path can never drift:
    legacy('T'.toByte, sender.publicKey) shouldBe legacy('W'.toByte, TxHelpers.signer(9).publicKey)
  }
}
