package com.decentralchain.account

import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.KeyLength
import play.api.libs.json.{Format, Writes}

opaque type PrivateKey = ByteStr

object PrivateKey {
  def apply(privateKey: ByteStr): PrivateKey = {
    require(privateKey.arr.length == KeyLength, s"invalid private key length: ${privateKey.arr.length}")
    privateKey
  }

  def apply(privateKey: Array[Byte]): PrivateKey =
    apply(ByteStr(privateKey))

  def unapply(arg: Array[Byte]): Option[PrivateKey] =
    Some(apply(arg))

  given Format[PrivateKey] = Format[PrivateKey](
    com.decentralchain.utils.byteStrFormat.map(this.apply),
    Writes(pk => com.decentralchain.utils.byteStrFormat.writes(pk))
  )

  extension (sk: PrivateKey) {
    def arr: Array[Byte] = sk.arr
  }
}
