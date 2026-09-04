package io.decentralchain.protobuf.block

import com.google.protobuf.ByteString
import com.decentralchain.common.utils.EitherExt2.explicitGet
import com.decentralchain.crypto.bls.BlsSignature
import io.decentralchain.protobuf.*
import com.decentralchain.state.{GeneratorIndex, Height}

import scala.util.Try

object PBFinalizationVotings {
  def vanilla(pb: PBFinalizationVoting): Try[VanillaFinalizationVoting] = Try {
    val aggSig =
      if (pb.aggregatedEndorsementSignature.isEmpty) None
      else Option(BlsSignature(pb.aggregatedEndorsementSignature.toByteArray).explicitGet())

    VanillaFinalizationVoting(
      GeneratorIndex.seq(pb.endorserIndexes),
      Height(pb.finalizedBlockHeight),
      aggSig,
      pb.conflictEndorsements.zipWithIndex.map { case (x, i) =>
        BlsSignature(x.signature.toByteArray).map(PBEndorseBlocks.vanilla(x, _)) match {
          case Left(e)  => throw new IllegalArgumentException(s"Error during parsing conflict endorsement #$i: $e")
          case Right(r) => r
        }
      }.toVector,
      pb.hotstuffConflicts.map(PBHotStuffEquivocationProofs.vanilla).toVector
    )
  }

  def protobuf(v: VanillaFinalizationVoting): PBFinalizationVoting = PBFinalizationVoting.of(
    GeneratorIndex.toInts(v.valid),
    v.finalizedHeight.toInt,
    v.aggregatedEndorsement.fold(ByteString.EMPTY)(_.byteStr.toByteString),
    v.conflict.map(PBEndorseBlocks.protobuf),
    v.hotstuffConflicts.map(PBHotStuffEquivocationProofs.protobuf)
  )
}
