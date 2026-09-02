package io.decentralchain.protobuf

package object block {
  type PBBlock = io.decentralchain.protobuf.block.Block
  val PBBlock = io.decentralchain.protobuf.block.Block

  type VanillaBlock = com.decentralchain.block.Block
  val VanillaBlock = com.decentralchain.block.Block

  type PBBlockHeader = io.decentralchain.protobuf.block.Block.Header
  val PBBlockHeader = io.decentralchain.protobuf.block.Block.Header

  type VanillaBlockHeader = com.decentralchain.block.BlockHeader
  val VanillaBlockHeader = com.decentralchain.block.BlockHeader

  type PBSignedMicroBlock = io.decentralchain.protobuf.block.SignedMicroBlock
  val PBSignedMicroBlock = io.decentralchain.protobuf.block.SignedMicroBlock

  type PBMicroBlock = io.decentralchain.protobuf.block.MicroBlock
  val PBMicroBlock = io.decentralchain.protobuf.block.MicroBlock

  type VanillaMicroBlock = com.decentralchain.block.MicroBlock
  val VanillaMicroBlock = com.decentralchain.block.MicroBlock

  type PBEndorseBlock = io.decentralchain.protobuf.block.EndorseBlock
  val PBEndorseBlock = io.decentralchain.protobuf.block.EndorseBlock

  type VanillaFinalizationVoting = com.decentralchain.block.FinalizationVoting
  val VanillaFinalizationVoting = com.decentralchain.block.FinalizationVoting

  type PBFinalizationVoting = io.decentralchain.protobuf.block.FinalizationVoting
  val PBFinalizationVoting = io.decentralchain.protobuf.block.FinalizationVoting

  type PBHotStuffEquivocationProof = io.decentralchain.protobuf.block.HotStuffEquivocationProof
  val PBHotStuffEquivocationProof = io.decentralchain.protobuf.block.HotStuffEquivocationProof
}
