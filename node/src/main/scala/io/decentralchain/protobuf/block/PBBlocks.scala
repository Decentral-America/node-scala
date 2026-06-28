package io.decentralchain.protobuf.block

import com.google.protobuf.ByteString
import com.decentralchain.account.AddressScheme
import com.decentralchain.block.{BlockHeader, ChallengedHeader}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import io.decentralchain.protobuf.block.Block.Header as PBHeader
import io.decentralchain.protobuf.transaction.PBTransactions
import io.decentralchain.protobuf.transaction.SignedTransaction.Transaction
import io.decentralchain.protobuf.{toByteStr, toByteString, toPublicKey}

import scala.util.Try

object PBBlocks {
  def vanilla(header: PBBlock.Header): BlockHeader =
    BlockHeader(
      header.version.toByte,
      header.timestamp,
      header.reference.toByteStr,
      header.baseTarget,
      header.generationSignature.toByteStr,
      header.generator.toPublicKey,
      header.featureVotes.map(_.toShort),
      header.rewardVote,
      header.transactionsRoot.toByteStr,
      Option.unless(header.stateHash.isEmpty)(header.stateHash.toByteStr),
      header.challengedHeader.map { ch =>
        ChallengedHeader(
          ch.timestamp,
          ch.baseTarget,
          ch.generationSignature.toByteStr,
          ch.featureVotes.map(_.toShort),
          ch.generator.toPublicKey,
          ch.rewardVote,
          Option.unless(ch.stateHash.isEmpty)(ch.stateHash.toByteStr),
          ch.headerSignature.toByteStr,
          ch.finalizationVoting.map(PBFinalizationVotings.vanilla(_).get)
        )
      },
      header.finalizationVoting.map(PBFinalizationVotings.vanilla(_).get),
      Option.unless(header.committedGeneratorsHash.isEmpty)(header.committedGeneratorsHash.toByteStr)
    )

  def vanilla(block: PBBlock, unsafe: Boolean = false): Try[VanillaBlock] = Try {
    require(block.header.isDefined, "block header is missing")
    VanillaBlock(vanilla(block.getHeader), block.signature.toByteStr, block.transactions.map(PBTransactions.vanilla(_, unsafe).explicitGet()))
  }

  def protobuf(header: BlockHeader): PBHeader = PBBlock.Header.of(
    AddressScheme.current.chainId,
    header.reference.toByteString,
    header.baseTarget,
    header.generationSignature.toByteString,
    header.featureVotes.map(_.toInt),
    header.timestamp,
    header.version,
    ByteString.copyFrom(header.generator.arr),
    header.rewardVote,
    header.transactionsRoot.toByteString,
    header.stateHash.getOrElse(ByteStr.empty).toByteString,
    header.challengedHeader.map { ch =>
      PBBlock.Header.ChallengedHeader.of(
        ch.baseTarget,
        ch.generationSignature.toByteString,
        ch.featureVotes.map(_.toInt),
        ch.timestamp,
        ch.generator.toByteString,
        ch.rewardVote,
        ch.stateHash.getOrElse(ByteStr.empty).toByteString,
        ch.headerSignature.toByteString,
        ch.finalizationVoting.map(PBFinalizationVotings.protobuf)
      )
    },
    header.finalizationVoting.map(PBFinalizationVotings.protobuf),
    header.committedGeneratorsHash.getOrElse(ByteStr.empty).toByteString
  )

  def protobuf(block: VanillaBlock): PBBlock = {
    import block.*

    new PBBlock(
      Some(protobuf(header)),
      ByteString.copyFrom(block.signature.arr),
      transactionData.map(PBTransactions.protobuf)
    )
  }

  def clearChainId(block: PBBlock): PBBlock =
    block.update(
      _.header.chainId := 0,
      _.transactions.foreach(_.transaction.modify {
        case Transaction.DccTransaction(value) => Transaction.DccTransaction(value.update(_.chainId := 0))
        case other                               => other
      })
    )

  def addChainId(block: PBBlock): PBBlock = {
    val chainId = AddressScheme.current.chainId

    block.update(
      _.header.chainId := chainId,
      _.transactions.foreach(_.transaction.modify {
        case Transaction.DccTransaction(value) => Transaction.DccTransaction(value.update(_.chainId := chainId))
        case other                               => other
      })
    )
  }
}
