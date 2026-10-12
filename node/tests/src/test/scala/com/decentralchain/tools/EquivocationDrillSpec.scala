package com.decentralchain.tools

import com.decentralchain.account.{Address, AddressScheme}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffEquivocationProof, HotStuffQuorum, HotStuffSafety}
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.test.FreeSpec

class EquivocationDrillSpec extends FreeSpec {
  "accountOf derives the same account as the TS SDK / node wallet (nonce 0)" in {
    val saved = AddressScheme.current
    try {
      AddressScheme.current = new AddressScheme { val chainId: Byte = '!'.toByte }
      // Public testnet treasury seed; its address is pinned in the funding scripts.
      Address.fromPublicKey(EquivocationDrill.accountOf("dcc-testnet-treasury").publicKey).toString shouldBe
        "31LQd8WcvtYXMjHzNKzRrgZy8P3NnLQvPnk"
    } finally AddressScheme.current = saved
  }

  "the vote pair is a valid, detectable equivocation" in {
    val voter  = EquivocationDrill.accountOf("equivocation drill test voter")
    val blockA = ByteStr(Array.fill[Byte](32)(1))
    val blockB = ByteStr(Array.fill[Byte](32)(2))
    val (a, b) = EquivocationDrill.equivocatingVotes(voter, voterIndex = 3, view = 55000, height = 55000, epoch = 549, blockA, blockB)
    val blsKey = BlsKeyPair(voter.privateKey).publicKey
    val keyOf  = (i: Int) => Option.when(i == 3)(blsKey)
    val proof  = HotStuffEquivocationProof(a, b)

    HotStuffSafety.equivocators(Seq(a, b)) shouldBe Set(3)
    proof.consistent shouldBe Right(())
    proof.signaturesValid(keyOf) shouldBe Right(())
    // Each vote alone is exactly what an honest replica would sign.
    Seq(a, b).foreach { v =>
      BlsKeyPair(voter.privateKey)
        .sign(
          HotStuffQuorum.voteMessage(v.view, v.phase, v.blockId, v.blockHeight.toInt, v.committeeEpoch),
          HotStuffQuorum.VoteDst
        )
        .byteStr shouldBe v.signature
    }
  }
}
