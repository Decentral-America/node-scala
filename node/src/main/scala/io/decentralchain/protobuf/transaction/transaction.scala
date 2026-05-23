package io.decentralchain.protobuf

//noinspection TypeAnnotation
package object transaction {
  type PBOrder = io.decentralchain.protobuf.order.Order
  val PBOrder = io.decentralchain.protobuf.order.Order

  type VanillaOrder = com.decentralchain.transaction.assets.exchange.Order
  val VanillaOrder = com.decentralchain.transaction.assets.exchange.Order

  type PBTransaction = io.decentralchain.protobuf.transaction.Transaction
  val PBTransaction = io.decentralchain.protobuf.transaction.Transaction

  type PBSignedTransaction = io.decentralchain.protobuf.transaction.SignedTransaction
  val PBSignedTransaction = io.decentralchain.protobuf.transaction.SignedTransaction

  type VanillaTransaction = com.decentralchain.transaction.Transaction
  val VanillaTransaction = com.decentralchain.transaction.Transaction

  type VanillaAssetId = com.decentralchain.transaction.Asset
}
