package com.decentralchain.blockchain

import com.decentralchain.block.SignedBlockHeader
import com.decentralchain.common.state.ByteStr

case class SignedBlockHeaderWithVrf(header: SignedBlockHeader, vrf: ByteStr, blockReward: Long)
