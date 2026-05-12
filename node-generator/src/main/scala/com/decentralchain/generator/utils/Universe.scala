package com.decentralchain.generator.utils

import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.lease.LeaseTransaction

object Universe {
  @volatile var IssuedAssets: List[IssueTransaction] = Nil
  @volatile var Leases: List[LeaseTransaction]       = Nil
}
