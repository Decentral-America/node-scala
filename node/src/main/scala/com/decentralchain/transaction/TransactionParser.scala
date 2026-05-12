package com.decentralchain.transaction

import com.decentralchain.transaction.validation.TxValidator

import scala.util.Try

trait TransactionParser {
  type TransactionT <: Transaction

  def typeId: TxType

  def parseBytes(bytes: Array[Byte]): Try[TransactionT]

  implicit def validator: TxValidator[TransactionT]
}
