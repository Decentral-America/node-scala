package com.decentralchain.consensus.hotstuff

/** Ingress sanity bounds for a wire-received `QuorumCertificate`/`HotStuffVote`'s `view`/`blockHeight`
  * (audit F-8, LOW). Both fields arrive off the wire with no range check anywhere upstream
  * (`QuorumCertificate.fromProtobuf`/`HotStuffVote.fromProtobuf`, `messages.scala`) -- a raw signed
  * int32. Deliberately a standalone, PURE object rather than logic inside `HotStuffEngine`
  * (which the audit and this codebase's own convention keep a pure reducer with no notion of "the
  * chain" to bounds-check against) -- this is called from the shell, at the `Application.scala`
  * ingress seam, where `blockchainUpdater.height` is actually available.
  *
  * Both an absurd `view` and an absurd `blockHeight` require passing full BLS quorum verification to
  * matter for real (`HotStuffEngine.onQC`/`onVote` verify before touching any state) -- i.e. a
  * colluding >= 2/3-stake committee, already a total protocol break -- so this is defense-in-depth
  * against a colluding quorum / a buggy signing path / a malformed-but-signed message from a buggy
  * peer version, not a mitigation for anything an unprivileged attacker can trigger on their own.
  * Without it: `view = Int.MaxValue` wraps the pacemaker to `Int.MinValue`
  * (`PacemakerState.onQC`'s `qcView + 1`), and `blockHeight = Int.MaxValue` permanently wedges
  * `HotStuffEngine.onQC`'s commit guard (`qc.blockHeight.toInt > advanced.committedHeight`), since no
  * real height can ever exceed it again -- both restart-surviving denials of service. Converting them
  * into a logged rejection here is cheap and turns a permanent wedge into an ordinary, observable
  * "bad message dropped".
  */
object HotStuffIngressGuard {

  /** @param view          the QC/vote's claimed pacemaker view.
    * @param blockHeight   the QC/vote's claimed target block height.
    * @param currentHeight this replica's own current chain height (`blockchainUpdater.height`).
    * @param slack         how far above `currentHeight` a target is still considered plausible. Chosen
    *                      by the caller (`Application.scala`) as ONE `generationPeriodLength`: HotStuff
    *                      intentionally runs `settledDepth` blocks BEHIND the tip
    *                      (`HotStuffSettings.settledDepth`), and F-6 documents HotStuff's lag as
    *                      legitimately able to grow up to roughly one generation period before other
    *                      mechanisms intervene -- so a target within one full period of this replica's
    *                      own tip is still a plausible, honestly-signed height (e.g. this replica is
    *                      itself lagging behind a faster peer), while anything further out is not a
    *                      height any real chain state could justify voting on right now. A simpler fixed
    *                      constant would work too; tying it to `generationPeriodLength` keeps the bound
    *                      meaningful across mainnet/testnet's differing block-rate configurations instead
    *                      of picking an arbitrary number.
    *
    *                      The caller floors this at 1000 (`math.max(generationPeriodLength, 1000)`):
    *                      this doc originally assumed `generationPeriodLength` in the 1000-10000 range,
    *                      but live testnet overrides it down to 100
    *                      (`infra/node-config/testnet/dcc.conf`), which would leave the deployed margin
    *                      an order of magnitude thinner than documented above. The floor exists because
    *                      this guard's job is wedge-prevention, not tight bounding -- a few thousand
    *                      blocks of extra slack is free (still trivially far below `Int.MaxValue`) and
    *                      the only cost of being generous is a slightly later rejection of an already
    *                      quorum-signed, already-total-protocol-break message. See the F-8 review
    *                      follow-up finding (deployed-margin-vs-documented-margin mismatch) and
    *                      `HotStuffIngressGuardSpecification`'s floor-proving case.
    * @return `true` iff `view` is non-negative AND far enough from `Int.MaxValue` that
    *         `PacemakerState.onQC`'s `qcView + 1` cannot overflow, AND `blockHeight > 0`, AND
    *         `blockHeight <= currentHeight + slack`.
    */
  def sane(view: Int, blockHeight: Int, currentHeight: Int, slack: Int): Boolean = {
    // `view` has no natural chain-height-relative ceiling the way `blockHeight` does -- legitimate
    // pacemaker view-changes on timeout can run the view arbitrarily far ahead of block height (that
    // decoupling is deliberate, see `HotStuffCoordinator`'s view/height notes). The concrete hazard
    // this guards against is narrower and precise: `PacemakerState.onQC`'s `qcView + 1` overflowing
    // `Int` when `qcView` is at or near `Int.MaxValue`, which wraps the pacemaker to a negative view
    // and wedges every subsequent monotonicity check. So the upper bound here is deliberately just
    // "not close enough to Int.MaxValue for +1 to overflow", not an arbitrary ceiling tied to height.
    val viewSane = view >= 0 && view < Int.MaxValue
    viewSane && blockHeight > 0 && blockHeight <= currentHeight + slack
  }
}
