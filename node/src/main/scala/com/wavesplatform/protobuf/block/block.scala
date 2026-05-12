package com.wavesplatform.protobuf

package object block {
  type PBBlock = com.wavesplatform.protobuf.block.Block
  val PBBlock = com.wavesplatform.protobuf.block.Block

  type VanillaBlock = com.decentralchain.block.Block
  val VanillaBlock = com.decentralchain.block.Block

  type PBBlockHeader = com.wavesplatform.protobuf.block.Block.Header
  val PBBlockHeader = com.wavesplatform.protobuf.block.Block.Header

  type VanillaBlockHeader = com.decentralchain.block.BlockHeader
  val VanillaBlockHeader = com.decentralchain.block.BlockHeader

  type PBSignedMicroBlock = com.wavesplatform.protobuf.block.SignedMicroBlock
  val PBSignedMicroBlock = com.wavesplatform.protobuf.block.SignedMicroBlock

  type PBMicroBlock = com.wavesplatform.protobuf.block.MicroBlock
  val PBMicroBlock = com.wavesplatform.protobuf.block.MicroBlock

  type VanillaMicroBlock = com.decentralchain.block.MicroBlock
  val VanillaMicroBlock = com.decentralchain.block.MicroBlock

  type PBEndorseBlock = com.wavesplatform.protobuf.block.EndorseBlock
  val PBEndorseBlock = com.wavesplatform.protobuf.block.EndorseBlock

  type VanillaFinalizationVoting = com.decentralchain.block.FinalizationVoting
  val VanillaFinalizationVoting = com.decentralchain.block.FinalizationVoting

  type PBFinalizationVoting = com.wavesplatform.protobuf.block.FinalizationVoting
  val PBFinalizationVoting = com.wavesplatform.protobuf.block.FinalizationVoting
}
