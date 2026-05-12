package com.decentralchain.state

import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.*
import com.decentralchain.lagonaki.mocks.TestBlock

trait HistoryTest {
  val genesisBlock: Block = TestBlock.withReference(ByteStr(Array.fill(SignatureLength)(0: Byte))).block

  def getNextTestBlock(blockchain: Blockchain): Block =
    TestBlock.withReference(blockchain.lastBlockId.get).block

  def getNextTestBlockWithVotes(blockchain: Blockchain, votes: Seq[Short]): Block =
    TestBlock.withReferenceAndFeatures(blockchain.lastBlockId.get, votes).block
}
