package io.decentralchain.protobuf.block

import com.decentralchain.account.PublicKey
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.network.MicroBlockResponse
import io.decentralchain.protobuf.*
import io.decentralchain.protobuf.transaction.PBTransactions

import scala.util.{Failure, Success, Try}

object PBMicroBlocks {
  def vanilla(signedMicro: PBSignedMicroBlock, unsafe: Boolean = false): Try[MicroBlockResponse] = Try {
    require(signedMicro.microBlock.isDefined, "microblock is missing")
    val microBlock   = signedMicro.getMicroBlock
    val transactions = microBlock.transactions.map(PBTransactions.vanilla(_, unsafe).explicitGet())

    val finalizationVoting = microBlock.finalizationVoting.map { x =>
      PBFinalizationVotings.vanilla(x) match {
        case Failure(e) => throw new RuntimeException(s"Can't decode $x as a vanilla finalization voting: ${e.getMessage}", e)
        case Success(x) => x
      }
    }

    MicroBlockResponse(
      VanillaMicroBlock(
        microBlock.version.toByte,
        PublicKey(microBlock.senderPublicKey.toByteArray),
        transactions,
        microBlock.reference.toByteStr,
        microBlock.updatedBlockSignature.toByteStr,
        signedMicro.signature.toByteStr,
        Option.unless(microBlock.stateHash.isEmpty)(microBlock.stateHash.toByteStr),
        finalizationVoting
      ),
      signedMicro.totalBlockId.toByteStr
    )
  }

  def protobuf(microBlock: VanillaMicroBlock, totalBlockId: BlockId): PBSignedMicroBlock =
    new PBSignedMicroBlock(
      microBlock = Some(
        PBMicroBlock(
          version = microBlock.version,
          reference = microBlock.reference.toByteString,
          updatedBlockSignature = microBlock.totalResBlockSig.toByteString,
          senderPublicKey = microBlock.sender.toByteString,
          transactions = microBlock.transactionData.map(PBTransactions.protobuf),
          stateHash = microBlock.stateHash.getOrElse(ByteStr.empty).toByteString,
          finalizationVoting = microBlock.finalizationVoting.map(PBFinalizationVotings.protobuf)
        )
      ),
      signature = microBlock.signature.toByteString,
      totalBlockId = totalBlockId.toByteString
    )
}
