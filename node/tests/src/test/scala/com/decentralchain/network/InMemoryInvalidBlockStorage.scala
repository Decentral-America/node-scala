package com.decentralchain.network

import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.TxValidationError.GenericError

class InMemoryInvalidBlockStorage extends InvalidBlockStorage {

  var s: Set[ByteStr] = Set.empty[ByteStr]

  override def add(blockId: ByteStr, validationError: ValidationError): Unit = s += blockId

  override def find(blockId: ByteStr): Option[ValidationError] = {
    if (s.contains(blockId)) Some(GenericError("Unknown")) else None
  }

}
