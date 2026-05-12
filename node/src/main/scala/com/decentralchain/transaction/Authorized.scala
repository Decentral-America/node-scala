package com.decentralchain.transaction
import com.decentralchain.account.PublicKey

trait Authorized {
  def sender: PublicKey
}
