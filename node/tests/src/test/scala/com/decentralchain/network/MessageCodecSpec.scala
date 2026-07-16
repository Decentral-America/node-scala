package com.decentralchain.network

import java.nio.charset.StandardCharsets

import com.decentralchain.common.state.ByteStr
import com.decentralchain.state.Height
import com.decentralchain.test.FreeSpec
import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.{ProvenTransaction, Transaction}
import io.decentralchain.protobuf.block.HotStuffPhase
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel

class MessageCodecSpec extends FreeSpec {

  // Regression: MessageCodec.encode is a hand-written per-type dispatch; HotStuff message types were
  // missing, so the encoder threw "unsupported" and silently dropped every outbound HotStuff message —
  // QCs never formed across nodes on the live testnet. Each type must encode to RawBytes and decode back.
  "encodes and decodes every HotStuff message type (must not hit the 'unsupported' default)" in {
    val blockId  = ByteStr(Array.fill[Byte](32)(7))
    val sig      = ByteStr(Array.fill[Byte](96)(3)) // BLS signature length
    val qc       = QuorumCertificate(5, HotStuffPhase.HOTSTUFF_PHASE_COMMIT, blockId, Height(1234), Seq(0, 1, 3), sig)
    val messages = Seq[Message](
      HotStuffVote(5, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockId, Height(1234), 2, sig),
      qc,
      HotStuffProposal(5, blockId, Some(qc))
    )
    messages.foreach { msg =>
      val ch = new EmbeddedChannel(new MessageCodec(PeerDatabase.NoOp))
      ch.writeOutbound(msg) // encode: throws IllegalArgumentException("unsupported") if a case is missing
      val raw = ch.readOutbound[RawBytes]()
      raw should not be null
      ch.writeInbound(raw) // decode back via specsByCodes
      ch.readInbound[Message]() shouldBe msg
    }
  }

  "should block a sender of invalid messages" in {
    val codec = new SpyingMessageCodec
    val ch    = new EmbeddedChannel(codec)

    ch.writeInbound(RawBytes(TransactionSpec.messageCode, "foo".getBytes(StandardCharsets.UTF_8)))
    ch.readInbound[IssueTransaction]()

    codec.blockCalls shouldBe 1
  }

  "should not block a sender of valid messages" in forAll(randomTransactionGen) { (origTx: Transaction & ProvenTransaction) =>
    val codec = new SpyingMessageCodec
    val ch    = new EmbeddedChannel(codec)

    ch.writeInbound(RawBytes.fromTransaction(origTx))
    val decodedTx = ch.readInbound[Transaction]()

    decodedTx shouldBe origTx
    codec.blockCalls shouldBe 0
  }

  private class SpyingMessageCodec extends MessageCodec(PeerDatabase.NoOp) {
    var blockCalls = 0

    override def block(ctx: ChannelHandlerContext, e: Throwable): Unit = {
      blockCalls += 1
    }
  }

}
