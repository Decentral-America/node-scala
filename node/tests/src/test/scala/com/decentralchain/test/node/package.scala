package com.decentralchain.test

import java.util.concurrent.ThreadLocalRandom

import com.decentralchain.account.{Address, KeyPair}
import com.decentralchain.crypto.KeyLength

package object node {
  def randomKeyPair(): KeyPair = {
    val seed = new Array[Byte](KeyLength)
    ThreadLocalRandom.current().nextBytes(seed)
    KeyPair(seed)
  }

  def randomAddress(): Address = {
    val seed = new Array[Byte](Address.HashLength)
    ThreadLocalRandom.current().nextBytes(seed)
    Address(seed)
  }
}
