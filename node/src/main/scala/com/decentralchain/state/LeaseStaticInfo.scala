package com.decentralchain.state

import com.decentralchain.account.{Address, PublicKey}
import com.decentralchain.transaction.TxPositiveAmount

case class LeaseStaticInfo(
    sender: PublicKey,
    recipientAddress: Address,
    amount: TxPositiveAmount,
    sourceId: TransactionId,
    height: Height
)
