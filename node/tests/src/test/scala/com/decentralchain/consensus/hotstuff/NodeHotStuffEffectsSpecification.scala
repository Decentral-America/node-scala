package com.decentralchain.consensus.hotstuff

import com.decentralchain.TestWallet
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.state.GeneratorSet
import com.decentralchain.test.FlatSpec
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor

import java.util.concurrent.atomic.AtomicInteger

/** Proves the `authoritative` gate on `NodeHotStuffEffects.onCommit`:
  *   - default (authoritative=false): a HotStuff commit NEVER invokes the authoritative-raise hook --
  *     today's fully-observational behaviour, byte-for-byte unchanged.
  *   - authoritative=true: a genuine commit DOES invoke the hook with the committed (blockId, height).
  *
  * This is deliberately independent of any real `BlockchainUpdaterImpl` -- the hook is injected as a
  * plain function so this spec proves the GATING decision in isolation from the state-mutation mechanism
  * (covered separately, against a real chain, by `HotStuffAuthoritativeFinalitySpec`).
  */
class NodeHotStuffEffectsSpecification extends FlatSpec with TestWallet {
  private def mkChannelGroup                     = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
  private val emptyCommittee: () => GeneratorSet = () => Seq.empty
  private def randomBlockId: BlockId             = ByteStr(Array.fill(32)(0: Byte))

  "onCommit" should "NOT invoke the authoritative-raise hook when authoritative=false (default; today's observational behaviour)" in {
    val raiseCalls = new AtomicInteger(0)
    val effects    = new NodeHotStuffEffects(
      emptyCommittee,
      testWallet,
      mkChannelGroup,
      authoritative = false,
      raiseFinalizedHeight = (_, _) => { raiseCalls.incrementAndGet(); true }
    )

    effects.onCommit(randomBlockId, 42)

    raiseCalls.get() shouldBe 0
    effects.hotStuffFinalizedHeight shouldBe 42 // observational counter still advances
  }

  it should "invoke the authoritative-raise hook exactly once with the committed (blockId, height) when authoritative=true" in {
    val raiseCalls                       = new AtomicInteger(0)
    var seenArgs: Option[(BlockId, Int)] = None
    val committedId                      = randomBlockId
    val effects                          = new NodeHotStuffEffects(
      emptyCommittee,
      testWallet,
      mkChannelGroup,
      authoritative = true,
      raiseFinalizedHeight = (id, h) => {
        raiseCalls.incrementAndGet()
        seenArgs = Some((id, h))
        true
      }
    )

    effects.onCommit(committedId, 7)

    raiseCalls.get() shouldBe 1
    seenArgs shouldBe Some((committedId, 7))
    effects.hotStuffFinalizedHeight shouldBe 7
  }

  it should "still advance the observational counter when authoritative=true and the hook REFUSES the raise (disagreement)" in {
    val effects = new NodeHotStuffEffects(
      emptyCommittee,
      testWallet,
      mkChannelGroup,
      authoritative = true,
      raiseFinalizedHeight = (_, _) => false // simulates a canonical-chain mismatch refusal
    )

    effects.onCommit(randomBlockId, 99)

    effects.hotStuffFinalizedHeight shouldBe 99
  }
}
