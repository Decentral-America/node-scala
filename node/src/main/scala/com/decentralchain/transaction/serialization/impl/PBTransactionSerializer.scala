package com.decentralchain.transaction.serialization.impl

import cats.syntax.applicativeError.*
import io.decentralchain.protobuf.transaction.{PBTransactions, SignedTransaction as PBSignedTransaction}
import io.decentralchain.protobuf.utils.PBUtils
import com.decentralchain.transaction.{PBParsingError, Transaction}

import scala.util.Try

object PBTransactionSerializer {
  def bodyBytes(tx: Transaction): Array[Byte] =
    PBUtils.encodeDeterministic(PBTransactions.protobuf(tx).getWavesTransaction)

  def bytes(tx: Transaction): Array[Byte] =
    PBUtils.encodeDeterministic(PBTransactions.protobuf(tx))

  def parseBytes(bytes: Array[Byte]): Try[Transaction] =
    PBSignedTransaction
      .validate(bytes)
      .adaptErr { case err => PBParsingError(err) }
      .flatMap(PBTransactions.tryToVanilla)
}
