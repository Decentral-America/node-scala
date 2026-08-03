package com.decentralchain.finalization

import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.state.Height
import com.decentralchain.transaction.TxHelpers

/** T2 HotStuff authoritative-finality hook (`BlockchainUpdaterImpl.raiseHotStuffFinalizedHeight`),
  * exercised directly against a real `Domain`/`BlockchainUpdaterImpl` -- independent of
  * `NodeHotStuffEffects`'s own authoritative gate (covered by `NodeHotStuffEffectsSpecification`).
  *
  * Design under test: a HotStuff `commitQC` may only ever RAISE the persisted feature-25
  * `finalizedHeight`, and only for a block this node's OWN canonical/synced chain already has at that
  * exact height -- see docs/hotstuff-integration-design.md and the T10 cross-epoch-fork hazard in
  * docs/hotstuff-audit-readiness.md. This is the safety property audited here (RED tests (b)-(d) from
  * the task): agreement is required, disagreement is refused, and the result is monotonic.
  */
class HotStuffAuthoritativeFinalitySpec extends BaseFinalizationSpec {
  private val node0Acc = TxHelpers.signer(0)
  private val settings = DomainPresets.DeterministicFinality

  "raiseHotStuffFinalizedHeight" - {
    "raises finalizedHeight past feature-25's own floor for a block already on the canonical chain" in withDomain(
      settings,
      AddrWithBalance.enoughBalances(node0Acc)
    ) { d =>
      (1 to 5).foreach(_ => d.appendBlock())
      val floorBefore    = d.blockchain.finalizedHeight.fold(0)(_.toInt)
      val targetHeight   = Height(d.blockchain.height)
      val targetBlockId  = d.blockchain.blockId(targetHeight.toInt).value

      val applied = d.blockchainUpdater.raiseHotStuffFinalizedHeight(targetBlockId, targetHeight)

      applied shouldBe true
      d.blockchain.finalizedHeight.value.toInt should be > floorBefore
      d.blockchain.finalizedHeight.value.toInt shouldBe targetHeight.toInt
    }

    "REFUSES to raise when the certified blockId does NOT match the local canonical chain at that height" in withDomain(
      settings,
      AddrWithBalance.enoughBalances(node0Acc)
    ) { d =>
      (1 to 5).foreach(_ => d.appendBlock())
      val floorBefore     = d.blockchain.finalizedHeight
      val targetHeight    = Height(d.blockchain.height)
      val foreignBlockId  = TxHelpers.randomBlockId // NOT the real block at targetHeight

      val applied = d.blockchainUpdater.raiseHotStuffFinalizedHeight(foreignBlockId, targetHeight)

      applied shouldBe false
      d.blockchain.finalizedHeight shouldBe floorBefore // completely unchanged -- the single most important safety test
    }

    "never regresses -- a lower/stale certified height arriving after a higher one is a no-op (idempotent max)" in withDomain(
      settings,
      AddrWithBalance.enoughBalances(node0Acc)
    ) { d =>
      (1 to 10).foreach(_ => d.appendBlock())
      val highHeight  = Height(d.blockchain.height)
      val highBlockId = d.blockchain.blockId(highHeight.toInt).value

      d.blockchainUpdater.raiseHotStuffFinalizedHeight(highBlockId, highHeight) shouldBe true
      val afterHigh = d.blockchain.finalizedHeight.value

      val lowHeight  = Height(highHeight.toInt - 5)
      val lowBlockId = d.blockchain.blockId(lowHeight.toInt).value
      d.blockchainUpdater.raiseHotStuffFinalizedHeight(lowBlockId, lowHeight) // may return true (it's a real, agreeing block) or be a genuine no-op; either way must never regress

      d.blockchain.finalizedHeight.value shouldBe afterHigh

      // re-delivering the SAME already-applied high QC again is a pure idempotent no-op
      d.blockchainUpdater.raiseHotStuffFinalizedHeight(highBlockId, highHeight)
      d.blockchain.finalizedHeight.value shouldBe afterHigh
    }
  }
}
