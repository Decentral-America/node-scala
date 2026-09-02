package io.decentralchain.protobuf.block

import com.decentralchain.consensus.hotstuff.HotStuffEquivocationProof
import com.decentralchain.network.HotStuffVote

object PBHotStuffEquivocationProofs {

  /** STRICT decode: throws IllegalArgumentException (failing the whole block parse, exactly like a
    * malformed conflict endorsement in PBFinalizationVotings) on a missing vote or on top-level
    * fields that disagree with vote_a. Proofs are consensus-critical inputs to conflictGenerators —
    * silent drops would both hide a framing attempt (design finding C3) and break decode/re-encode
    * round-trip identity for header bytes. Deterministic: same bytes reject identically everywhere.
    */
  def vanilla(pb: PBHotStuffEquivocationProof): HotStuffEquivocationProof = {
    val pbA   = pb.voteA.getOrElse(throw new IllegalArgumentException("HotStuffEquivocationProof missing vote_a"))
    val pbB   = pb.voteB.getOrElse(throw new IllegalArgumentException("HotStuffEquivocationProof missing vote_b"))
    val proof = HotStuffEquivocationProof(HotStuffVote.fromProtobuf(pbA), HotStuffVote.fromProtobuf(pbB))
    if (pb.voterIndex != proof.voterIndex || pb.view != proof.view || pb.phase != proof.phase)
      throw new IllegalArgumentException(
        s"HotStuffEquivocationProof top-level fields (voter=${pb.voterIndex}, view=${pb.view}, phase=${pb.phase}) " +
          s"disagree with vote_a (voter=${proof.voterIndex}, view=${proof.view}, phase=${proof.phase})"
      )
    proof
  }

  def protobuf(x: HotStuffEquivocationProof): PBHotStuffEquivocationProof = PBHotStuffEquivocationProof.of(
    voterIndex = x.voterIndex,
    view = x.view,
    phase = x.phase,
    voteA = Some(x.voteA.toProtobuf),
    voteB = Some(x.voteB.toProtobuf)
  )
}
