package com.decentralchain.consensus.hotstuff

import com.decentralchain.test.FlatSpec

/** Audit finding F-8 (LOW) regression coverage: a wire-received QC/vote's `view`/`blockHeight` reached
  * `HotStuffEngine`/`HotStuffPacemaker` completely unvalidated. `view = Int.MaxValue` wraps the
  * pacemaker (`PacemakerState.onQC`'s `qcView + 1` overflows to `Int.MinValue`); `blockHeight =
  * Int.MaxValue` permanently wedges the commit guard (`qc.blockHeight.toInt > advanced.committedHeight`
  * can never again be satisfied). Both require passing full BLS quorum verification to matter for real
  * (a colluding >= 2/3-stake committee), so this is defense-in-depth turning a permanent, restart-
  * surviving wedge into a logged, ordinary rejection -- see `HotStuffIngressGuard`'s doc.
  */
class HotStuffIngressGuardSpecification extends FlatSpec {
  private val currentHeight = 900_000
  // 1000 is the floor `Application.scala` applies via `math.max(generationPeriodLength, 1000)`, NOT
  // live testnet's raw `generationPeriodLength` -- that is 100 (infra/node-config/testnet/dcc.conf),
  // which is exactly why the floor exists (review follow-up to F-8: deployed margin was 10x thinner
  // than this doc assumed). `HotStuffIngressGuard.sane` itself takes `slack` as a plain Int and knows
  // nothing about the floor; the case below proves the floor's effect at the value the wiring actually
  // computes for testnet.
  private val slack = 1000

  "HotStuffIngressGuard.sane" should "accept normal, plausible values" in {
    HotStuffIngressGuard.sane(view = 42, blockHeight = currentHeight - 3, currentHeight, slack) should be(true)
    HotStuffIngressGuard.sane(view = 0, blockHeight = 1, currentHeight, slack) should be(true)
    // Within one generation period ahead of the tip (this replica itself lagging behind a faster peer).
    HotStuffIngressGuard.sane(view = 42, blockHeight = currentHeight + slack, currentHeight, slack) should be(true)
  }

  it should "reject an absurd view (Int.MaxValue) that would overflow the pacemaker" in {
    HotStuffIngressGuard.sane(view = Int.MaxValue, blockHeight = currentHeight, currentHeight, slack) should be(false)
  }

  it should "reject a negative view" in {
    HotStuffIngressGuard.sane(view = -1, blockHeight = currentHeight, currentHeight, slack) should be(false)
    HotStuffIngressGuard.sane(view = Int.MinValue, blockHeight = currentHeight, currentHeight, slack) should be(false)
  }

  it should "reject blockHeight = 0 (the pre-genesis sentinel, never a real proposable block)" in {
    HotStuffIngressGuard.sane(view = 1, blockHeight = 0, currentHeight, slack) should be(false)
  }

  it should "reject a negative blockHeight" in {
    HotStuffIngressGuard.sane(view = 1, blockHeight = -5, currentHeight, slack) should be(false)
  }

  it should "reject blockHeight = Int.MaxValue, which would permanently wedge the commit guard" in {
    HotStuffIngressGuard.sane(view = 1, blockHeight = Int.MaxValue, currentHeight, slack) should be(false)
  }

  it should "reject a blockHeight further ahead of the tip than one generation period's worth of slack" in {
    HotStuffIngressGuard.sane(view = 1, blockHeight = currentHeight + slack + 1, currentHeight, slack) should be(false)
  }

  it should "prove the Application.scala floor: testnet's raw generationPeriodLength (100) would let " +
    "through a target the floored slack (1000) correctly rejects" in {
      val rawTestnetGenerationPeriodLength = 100
      val flooredSlack                     = math.max(rawTestnetGenerationPeriodLength, 1000)
      flooredSlack should be(1000)

      val target = currentHeight + rawTestnetGenerationPeriodLength + 1
      // Without the floor (raw testnet generationPeriodLength as slack), this target would be rejected --
      // demonstrating the pre-fix margin was 10x thinner than intended.
      HotStuffIngressGuard.sane(view = 1, target, currentHeight, slack = rawTestnetGenerationPeriodLength) should be(
        false
      )
      // With the floor Application.scala actually applies, the same target is still plausible slack.
      HotStuffIngressGuard.sane(view = 1, target, currentHeight, slack = flooredSlack) should be(true)
    }
}
