package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.crypto.bls.BlsSignature
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message, QuorumCertificate}
import com.decentralchain.state.{GeneratorSet, Height}
import com.typesafe.scalalogging.StrictLogging
import io.decentralchain.protobuf.block.HotStuffPhase

/** Side-effect sink injected into the HotStuff coordinator. The real (step 4c-bind) implementation
  * broadcasts via `allChannels`, signs with the node's committed BLS key(s), and applies commits to
  * the blockchain finalized height; the simulation test injects an in-memory fake.
  */
trait HotStuffEffects {

  /** Send a message to peers. */
  def broadcast(message: Message): Unit

  /** Committee slots this node holds a BLS signing key for (normally just its own generator index). */
  def myVoterIndexes: Set[Int]

  /** Sign `voteMessage` as committee slot `voterIndex`, or None if this node doesn't hold that key. */
  def signVote(voteMessage: Array[Byte], voterIndex: Int): Option[BlsSignature]

  /** A block reached T2 finality — apply it (advance finalized height). */
  def onCommit(blockId: BlockId, height: Int): Unit
}

/** Orchestrates the pure reducers (`HotStuffEngine`, `HotStuffVotePool`, `HotStuffQuorum`) into the
  * 3-phase HotStuff loop for one node.
  */
sealed trait HotStuffCoordinator {
  def onProposal(proposal: HotStuffProposal, blockHeight: Int): Unit
  def onVote(vote: HotStuffVote): Unit
  def onQC(qc: QuorumCertificate): Unit
  def onLeaderTurn(view: Int, blockId: BlockId, blockHeight: Int): Unit
  def onTimeout(): Unit

  /** The pacemaker's current view (see `HotStuffPacemaker`/`PacemakerState`). `0` when disabled or
    * before any progress/timeout. Exposed so the shell can observe real view-change, and so
    * `HotStuffPacemaker.leaderFor(currentView, committee)` can answer "who should be proposing right
    * now" independently of block height (Task 8 Step 2 — see
    * docs/hotstuff-step5-findings-and-rework.md §4 Option A).
    */
  def currentView: Int

  /** Real round-timer entry point (replaces calling `onTimeout()` directly from the scheduler). Detects
    * whether the current view actually made progress (a QC formed, advancing the pacemaker) since the
    * PREVIOUS tick; if not, the leader is presumed silent/faulty, so this is a genuine leader-timeout,
    * not just a heartbeat: the pacemaker view advances via `onTimeout()` and, if THIS replica is the
    * deterministically-rotated leader for the new view (`HotStuffPacemaker.leaderFor`/`isLeader`), it
    * proposes immediately (self-driven view-change), instead of only ever proposing when an externally
    * supplied FairPoS-forger check happens to say so. No-op (never proposes) if no `blockSource` was
    * supplied at construction, or it has nothing to propose right now.
    */
  def onRoundTimerTick(): Unit

  /** Task 4 (wedged-committee watchdog) additive recovery hook: clear this replica's in-memory HotStuff
    * safety lock (`SafetyState.lockedQC`/`prepareQC`) so the NEXT event/tick starts from a blank safety
    * slate, same as a fresh process boot with no persisted lock. `refreshCommittee()` already runs at
    * the top of every existing entry point (including `onRoundTimerTick`), so no separate "refresh
    * committee" call is needed here -- clearing the lock is the only additional state mutation required
    * to reproduce the manual `rm locked-qc.dat` + restart's IN-PROCESS effect. Does NOT touch the
    * on-disk `locked-qc.dat` file itself (the watchdog deletes that separately -- see
    * `HotStuffWatchdog` -- since this coordinator has no filesystem path of its own) and, critically,
    * has NO reference to `finalizedHeight`/`BlockchainUpdaterImpl`/feature-25 at all: this method's
    * entire blast radius is the private `var`s it touches. Default no-op on `Disabled` (HotStuff gated
    * off -- there is no lock to clear) and on any existing test/call site that never invokes it,
    * preserving byte-for-byte prior behaviour everywhere this isn't explicitly called.
    */
  def resetLocalSafetyState(): Unit = ()
}

object HotStuffCoordinator {

  /** No-op coordinator used when `dcc.hotstuff.enabled = false` (the default). Guarantees zero
    * behaviour change while T2 is gated off.
    */
  object Disabled extends HotStuffCoordinator {
    def onProposal(proposal: HotStuffProposal, blockHeight: Int): Unit    = ()
    def onVote(vote: HotStuffVote): Unit                                  = ()
    def onQC(qc: QuorumCertificate): Unit                                 = ()
    def onLeaderTurn(view: Int, blockId: BlockId, blockHeight: Int): Unit = ()
    def onTimeout(): Unit                                                 = ()
    def currentView: Int                                                  = 0
    def onRoundTimerTick(): Unit                                          = ()
  }

  object Enabled {

    /** Bounded retry cap for `onRoundTimerTick`'s in-flight-branch re-propose optimization
      * (adversarial-review finding, `consensus/hotstuff-repropose-locked-branch`): after this many
      * CONSECUTIVE leader-timeouts re-proposing the SAME unresolved `prepareQC` blockId, this replica
      * abandons it in favor of `blockSource()`'s fresh tip instead of retrying forever. Deliberately
      * small (a handful of stalled rounds is already a strong "this branch is stuck" signal -- e.g. a
      * mid-round committee change that permanently excludes it from ever regathering quorum again --
      * not a value meant to tolerate many more genuine transient hiccups than that); the point is to
      * bound the livelock, not to give the in-flight branch every possible chance.
      */
    val MaxConsecutiveReproposeAttempts: Int = 3
  }

  /** The active coordinator. Single-threaded — the shell MUST confine all calls to one actor/scheduler
    * thread (the mutable state is not synchronized).
    *
    * SAFETY-CRITICAL orchestration: validated by the in-process simulation test and (required) by
    * step-5 multi-node IT + external audit before mainnet enablement.
    */
  final class Enabled(
      committeeProvider: () => GeneratorSet,
      effects: HotStuffEffects,
      extendsBranch: (BlockId, BlockId) => Boolean,
      // Safety guard for HotStuff-over-FairPoS: does `blockId` live anywhere on THIS replica's own
      // canonical chain? A replica votes only for a proposal that matches its own settled chain, so a
      // Byzantine leader cannot make honest nodes vote for a fabricated block. Default permissive for
      // the in-memory sim.
      //
      // Deliberately NOT parameterized on `view` (see docs/hotstuff-step5-findings-and-rework.md §4
      // Option A and the RED test in HotStuffViewChangeSpecification this replaces): the original guard
      // was `(view, blockId) => blockchainUpdater.blockId(view).contains(blockId)`, i.e. "blockId is
      // literally the canonical block AT HEIGHT == view". That assumed view == the proposed block's
      // height, which holds in the per-height happy path (one view per settled height) but breaks the
      // instant a pacemaker-driven view-change advances `view` independently of height -- exactly
      // findings #2/#5's height/view conflation bug class, reintroduced at this guard instead of the
      // vote-message height field. Checking chain-membership of `blockId` alone is view-number-agnostic
      // by construction: it answers "is this a real block on MY chain" regardless of which view number
      // it is (re-)proposed under. View-ordering/lock safety is enforced separately and unconditionally
      // by `HotStuffSafety.safeToVote` (via `extendsBranch`/`lockedQC`/`lastVotedView`) inside
      // `HotStuffEngine.onProposal`, which already runs after this guard passes -- this guard's sole job
      // is rejecting proposals for blocks the replica doesn't independently recognize as real, not
      // reasoning about view ordering.
      proposalValid: BlockId => Boolean = _ => true,
      // What to (re-)propose if `onRoundTimerTick` finds THIS replica is the new leader after a
      // leader-timeout view-change (Task 8 Step 2). Returns `None` when there is nothing safe/settled
      // to propose yet. Defaults to `None` so existing call sites (the height-driven happy path in
      // `Application.scala`, and every pre-existing test) are unaffected: with no blockSource,
      // `onRoundTimerTick` degrades to a pure pacemaker-view bump, identical to bare `onTimeout()`.
      blockSource: () => Option[(BlockId, Int)] = () => None,
      // Independently re-derive a block's height from its blockId, the SAME way the receive path
      // (`messageObserver.hotStuffProposals` in Application.scala) already does via
      // `blockchainUpdater.heightOf(p.blockId)`, instead of trusting the literal `blockHeight` argument
      // `onLeaderTurn` is called with. Without this, the self-vote path relies on an unstated invariant
      // (a block's height never changes after `blockSource`/the caller reads it) rather than being
      // defended the same way the receive path defends itself. Defaults to `_ => None` so existing call
      // sites/tests (which have no blockchain to query) are unaffected: `getOrElse(blockHeight)` then
      // falls back to the literal argument exactly as before.
      heightOf: BlockId => Option[Int] = _ => None,
      // Closes the "post-restart lockedQC=None" narrowing documented at `HotStuffSafety.safeToVote`:
      // seeds THIS replica's `SafetyState` with a `lockedQC` recovered from local disk (see
      // `HotStuffLockedQCStore`), instead of always starting from a blank slate. `None` (the default)
      // preserves today's exact behaviour for every existing call site/test that doesn't pass this --
      // a fresh `SafetyState()`, same as before this change.
      initialLockedQC: Option[QuorumCertificate] = None,
      // Fires exactly once per genuine lockedQC advance (never on a no-op/rejected QC), with the new
      // lock, so the shell can persist it (see `HotStuffLockedQCStore.save` / Application.scala) and
      // survive a future restart via `initialLockedQC` above. Defaults to a no-op so existing call
      // sites/tests are unaffected -- and, per `dcc.hotstuff.enabled=false`'s `Disabled` coordinator
      // never constructing an `Enabled` at all, this callback (and any disk I/O it drives) simply never
      // runs when HotStuff is disabled.
      onLockedQCPersist: QuorumCertificate => Unit = _ => (),
      // T10 fix: the committee epoch (see `state.GenerationPeriod`) THIS replica currently believes is
      // active, re-read fresh alongside `committeeProvider` on every event (see `refreshCommittee`).
      // Used ONLY for the transition-gating decision in `HotStuffEngine.onQC`/`onProposal`
      // (`HotStuffQuorum.acceptableCommitteeEpoch`) -- i.e. "given what epoch I currently believe is
      // active, is an INCOMING QC/proposal's claimed epoch one I should accept". It is deliberately
      // NOT used to derive the epoch THIS replica signs into its own votes -- see `committeeEpochOf`
      // below, which is the fix for that (root-cause-of-cross-epoch-liveness-stall) concern. Defaults
      // to a constant `0`, matching the default `committeeEpoch` on `HotStuffVote`/`QuorumCertificate`/
      // `EngineState` -- so every existing call site/test that doesn't pass this observes byte-for-byte
      // the same behaviour as before this fix (0 always compares equal to 0 in
      // `HotStuffQuorum.acceptableCommitteeEpoch`). Production wiring supplies the real generation
      // period at the replica's live tip (see `Application.scala`).
      committeeEpochProvider: () => Int = () => 0,
      // ROOT-CAUSE FIX (2026-08-04, cross-committee-epoch LIVENESS stall -- distinct from the T10 FORK
      // hazard `committeeEpochProvider` above helps gate): before this parameter existed, the epoch a
      // vote was SIGNED under came from `committeeEpochProvider()` above -- i.e. the SIGNING REPLICA'S
      // OWN LOCAL TIP at the moment it happened to cast the vote, not anything about the vote's target.
      // Two fully honest, fully-synced replicas voting for the IDENTICAL `(view, phase, blockId,
      // blockHeight)` target could therefore sign DIFFERENT `committeeEpoch` values if their local tip
      // straddled a generation-period boundary at slightly different moments (ordinary propagation
      // skew, not an attack) -- and `HotStuffQuorum.formQC`'s `sameTarget` check (correctly, per the
      // T10 fix) then rejected that mixed bucket outright, permanently stalling QC formation at every
      // committee-epoch rotation boundary. See `HotStuffCrossEpochLivenessSpecification`.
      //
      // The fix: derive the SIGNED `committeeEpoch` as a PURE function of the vote's TARGET height
      // (`blockchain.generationPeriodOf(targetHeight).index` in production -- see `Application.scala`)
      // instead of the signer's live tip. Since `generationPeriodOf` for a FIXED height always returns
      // the same result no matter when it is computed, every honest replica voting on the SAME target
      // now deterministically computes the SAME epoch, closing the stall at its root rather than merely
      // bounding it. Applied at every vote-signing call site via `castVotes` (leader self-vote in
      // `onLeaderTurn` -> `onProposal` -> `castVotes`, replica vote in `onProposal` -> `castVotes`, and
      // phase-progression votes in `applyQC` -> `castVotes`) -- all of which already have the target
      // height in hand. Defaults to a constant `0`, matching every existing call site/test that doesn't
      // pass this and preserving byte-for-byte prior behaviour for them.
      committeeEpochOf: Int => Int = _ => 0,
      // Task 4 (wedged-committee watchdog) additive hook: fires exactly once per action emitted by a
      // GENUINELY VERIFIED, ACCEPTED QC (`HotStuffEngine.onQC`'s `Committed`/`EnteredView` -- see
      // `applyQC` below), i.e. real quorum-backed progress. Deliberately NOT fired for the `EnteredView`
      // that `HotStuffEngine.onTimeout` (via this class's private `onTimeout()`, called from
      // `onRoundTimerTick` on every stalled tick) unconditionally emits on a BARE pacemaker view-bump --
      // that action shares the same case class but means the opposite thing here: "no QC formed, the
      // round stalled, the view was bumped anyway so the next leader gets a turn". A wedged committee
      // ticks that bare-timeout path forever, bumping the view every single tick, so treating IT as
      // "progress" would make a stall-detector that never fires -- defeating the whole point of the
      // signal this hook exists to provide (see `HotStuffWatchdog`). Defaults to a no-op so every
      // existing call site/test that doesn't pass this observes byte-for-byte the same behaviour as
      // before this hook existed. Does NOT change any existing method signature.
      onAction: HotStuffAction => Unit = _ => ()
  ) extends HotStuffCoordinator
      with StrictLogging {
    private var engine = EngineState(committeeProvider(), safety = SafetyState(lockedQC = initialLockedQC), committeeEpoch = committeeEpochProvider())
    private var pool   = VotePool()
    private var voted  = Set.empty[(Int, HotStuffPhase, BlockId)] // per-target vote guard (prevents storms/loops)
    // Baseline for stall detection in `onRoundTimerTick`: the pacemaker view as of the PREVIOUS tick,
    // or `None` before the first tick. `None` ensures the very first tick only establishes the
    // baseline and never mistakes "no ticks have happened yet" for "the leader stalled".
    private var lastTickView: Option[Int] = None

    // Bounded escape valve for `inFlightBranch` re-proposing (adversarial-review finding, this branch):
    // tracks the blockId THIS replica most recently re-proposed via the `inFlightBranch` path, and how
    // many CONSECUTIVE leader-timeouts in a row it has been re-proposed without resolving (committing,
    // or being superseded by a different in-flight branch). Nothing previously aged `prepareQC` out on
    // its own -- only a strictly-higher-view QC forming ever replaced it -- so a branch that can never
    // re-gather quorum again (e.g. a mid-round committee change permanently shifts who counts toward
    // quorum for it) would otherwise be re-proposed identically on every single subsequent leader-timeout,
    // forever: a genuine livelock, with the old (pre-this-branch) behaviour of unconditionally falling
    // back to `blockSource()`'s universally-fresh tip strictly more robust here. Reset to `(None, 0)`
    // whenever the in-flight blockId changes or resolves (i.e. whenever this replica ends up NOT
    // re-proposing via the in-flight path on some tick), so a genuinely-recovering branch is never
    // penalized by attempts accumulated against a since-abandoned one.
    private var lastReproposedBlockId: Option[BlockId] = None
    private var reproposeAttempts: Int                 = 0

    // The committed-generator committee rotates per generation period; refresh it from the chain at
    // the start of each event so reducers always see the current period's set.
    private def refreshCommittee(): Unit =
      engine = engine.copy(committee = committeeProvider(), committeeEpoch = committeeEpochProvider())

    // Bounded eviction of superseded pool entries (memory-leak guard, audit finding 2026-07-25). A
    // target never resolves on its own — a losing-fork block, or junk votes broadcast for bogus
    // targets — so its bucket + committee-snapshot set would leak into `pool` forever with no other
    // removal path (buckets are only cleared on successful QC formation). Prune every target strictly
    // older than the currently-active view whenever the view advances. Margin of ONE view is required
    // for correctness: after a PREPARE QC the pacemaker is already at v+1 while this node is still
    // accumulating PRE_COMMIT/COMMIT votes for view v, so we retain `view >= pacemaker.view - 1` to
    // avoid evicting the active view's still-in-flight later phases. Pure reducer; no timers/threads.
    private def prunePool(): Unit =
      pool = HotStuffVotePool.pruneOlderThan(pool, engine.pacemaker.view - 1)

    private def bid(b: BlockId): String = b.toString.take(8)

    /** Cast this node's vote(s) for a target exactly once, then feed our own vote into our pool.
      *
      * `committeeEpoch` is derived from THIS TARGET's own `height` (`committeeEpochOf(height)`), NOT
      * from `engine.committeeEpoch` (the signer's live-tip belief, used only for the transition-gating
      * decision in `HotStuffEngine.onQC`/`onProposal`) -- see `committeeEpochOf`'s doc on the
      * constructor for why: a pure function of the fixed target height is what makes every honest
      * replica voting on the SAME target sign the SAME epoch, regardless of when each replica's own
      * local tip happens to cross a generation-period boundary.
      */
    private def castVotes(view: Int, phase: HotStuffPhase, blockId: BlockId, height: Int): Unit = {
      val key = (view, phase, blockId)
      if (!voted.contains(key)) {
        voted += key
        val epoch   = committeeEpochOf(height)
        val message = HotStuffQuorum.voteMessage(view, phase, blockId, height, epoch)
        val mine    = effects.myVoterIndexes
        logger.debug(
          s"[HotStuff] castVotes $phase v=$view b=${bid(blockId)} myIndexes=$mine committee=${engine.committee.size} epoch=$epoch"
        )
        mine.foreach { idx =>
          effects.signVote(message, idx) match {
            case Some(sig) =>
              val vote = HotStuffVote(view, phase, blockId, Height(height), idx, sig.byteStr, epoch)
              effects.broadcast(vote)
              onVote(vote) // count our own vote locally
            case None => logger.warn(s"[HotStuff] signVote returned None for idx=$idx (no BLS key for this committee slot?)")
          }
        }
      }
    }

    def onProposal(proposal: HotStuffProposal, blockHeight: Int): Unit = {
      refreshCommittee()
      if (!proposalValid(proposal.blockId)) {
        logger.debug(
          s"[HotStuff] onProposal v=${proposal.view} b=${bid(proposal.blockId)} REJECTED (not a block this replica recognizes on its own chain)"
        )
      } else {
        val (nextEngine, shouldVote) = HotStuffEngine.onProposal(engine, proposal, extendsBranch)
        engine = nextEngine
        logger.debug(s"[HotStuff] onProposal v=${proposal.view} b=${bid(proposal.blockId)} shouldVote=$shouldVote committee=${engine.committee.size}")
        if (shouldVote) castVotes(proposal.view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, proposal.blockId, blockHeight)
      }
    }

    def onVote(vote: HotStuffVote): Unit = {
      refreshCommittee()
      val (nextPool, maybeQC) = HotStuffVotePool.onVote(pool, vote, engine.committee)
      pool = nextPool
      // Pool-level instrumentation: distinct signers accumulated for this target and whether they clear
      // the 2/3 stake quorum. On QC formation the bucket is cleared, so report the QC's own signer set
      // instead of the (now-empty) bucket. High-volume => DEBUG (per the step-5 handoff: reduce from INFO
      // once the QC-formation fix is in). `committeeReady` guards the degenerate hasQuorum([], empty)==true
      // during the post-restart window before the committee has loaded from chain state.
      val key            = (vote.view, vote.phase, vote.blockId)
      val bucket         = nextPool.pending.getOrElse(key, Vector.empty)
      val voters         = bucket.map(_.voterIndex).distinct.sorted
      val committeeReady = engine.committee.nonEmpty
      val quorum         = committeeReady && HotStuffQuorum.hasQuorum(voters, engine.committee)
      logger.debug(
        maybeQC match {
          case Some(qc) =>
            s"[HotStuff] onVote from #${vote.voterIndex} ${vote.phase} v=${vote.view} b=${bid(vote.blockId)} -> QC formed, signers=${qc.signerIndexes.sorted}"
          case None =>
            s"[HotStuff] onVote from #${vote.voterIndex} ${vote.phase} v=${vote.view} b=${bid(vote.blockId)} pooledVoters=$voters quorumByStake=$quorum -> QC=false"
        }
      )
      // Safety net: after the blockHeight fix this should never fire. If it does, a non-empty bucket
      // reached quorum yet formQC rejected it — surface WHY loudly. Guarded so the empty-bucket /
      // empty-committee catch-up window cannot produce a false alarm.
      if (maybeQC.isEmpty && quorum && bucket.nonEmpty)
        logger.warn(
          s"[HotStuff] QUORUM REACHED but no QC v=${vote.view} ${vote.phase} b=${bid(vote.blockId)} " +
            s"— votes disagree on blockHeight=${bucket.map(_.blockHeight.toInt).distinct.sorted} (must be identical to form a QC)"
        )
      maybeQC.foreach { qc =>
        // Self-verify BEFORE broadcasting. `applyQC` re-reads the committee (`refreshCommittee`) and
        // re-verifies the QC; if the committee shifted between `onVote`'s read above and this read,
        // the node must NOT broadcast a QC it then locally rejects. Passing the broadcast as the
        // by-name `onAccepted` runs it only when local self-verification succeeds, at the same point
        // (before commit/phase-progression) the broadcast previously occupied.
        applyQC(qc, effects.broadcast(qc))
      }
    }

    def onQC(qc: QuorumCertificate): Unit = applyQC(qc, ()) // wire-received QC: no re-broadcast on accept

    /** Apply a QC to local state: refresh committee → `HotStuffEngine.onQC` (verify) → commit →
      * phase-progress → prune. `onAccepted` (by-name) runs exactly once, IFF the QC passes this node's
      * own re-verification (was not `Rejected`), positioned after verification but before commit/phase-
      * progression — the hook a self-formed QC uses to broadcast only after it self-verifies.
      */
    private def applyQC(qc: QuorumCertificate, onAccepted: => Unit): Unit = {
      refreshCommittee()
      val previousLockedQC      = engine.safety.lockedQC
      val (nextEngine, actions) = HotStuffEngine.onQC(engine, qc)
      engine = nextEngine
      // `HotStuffSafety.update` only ever advances `lockedQC` monotonically (never regresses, never
      // touches it on a rejected QC -- a rejected QC's `actions` never reach here with a changed safety
      // state). Persist exactly on a genuine advance, so a later restart can seed `initialLockedQC`
      // with THIS replica's actual last lock instead of `None`.
      engine.safety.lockedQC.foreach(newLock => if (!previousLockedQC.contains(newLock)) onLockedQCPersist(newLock))
      val rejected = actions.exists { case _: HotStuffAction.Rejected => true; case _ => false }
      val line     =
        s"[HotStuff] onQC ${qc.phase} v=${qc.view} b=${bid(qc.blockId)} signers=${qc.signerIndexes.size} rejected=$rejected actions=${actions.mkString(",")}"
      // A rejected QC is worth surfacing (committee/view skew, e.g. during post-restart catch-up); a
      // healthy QC is per-view chatter => DEBUG. The commit itself is logged by NodeHotStuffEffects.onCommit.
      if (rejected) logger.warn(line) else logger.debug(line)
      if (!rejected) onAccepted // e.g. broadcast a self-formed QC — only now that WE accept it
      // Task 4 watchdog hook: report every action from a QC that reached HERE (i.e. survived the
      // epoch/crypto verification `HotStuffEngine.onQC` applies) -- `Committed`, and the `EnteredView`
      // that specifically accompanies a verified QC (NOT the bare-timeout one; that path never calls
      // `applyQC`/reaches this line). A `Rejected` action never appears in `actions` alongside advanced
      // state (see `HotStuffEngine.onQC`: a rejected QC returns `(state, Seq(Rejected(...)))` with
      // `state` UNCHANGED), so reporting every element of `actions` here is exactly "real progress
      // happened", matching this hook's contract.
      actions.foreach { action =>
        onAction(action)
        action match {
          case HotStuffAction.Committed(blockId, height) => effects.onCommit(blockId, height)
          case _                                         => ()
        }
      }
      // Phase progression: on a verified QC, vote the next phase for the same block (guarded by `voted`).
      if (!rejected) {
        val nextPhase = qc.phase match {
          case HotStuffPhase.HOTSTUFF_PHASE_PREPARE    => Some(HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT)
          case HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT => Some(HotStuffPhase.HOTSTUFF_PHASE_COMMIT)
          case _                                       => None
        }
        nextPhase.foreach(p => castVotes(qc.view, p, qc.blockId, qc.blockHeight.toInt))
      }
      prunePool() // the view may have advanced — evict superseded targets (bounded-memory guard)
    }

    def onLeaderTurn(view: Int, blockId: BlockId, blockHeight: Int): Unit = {
      refreshCommittee()
      // Defense-in-depth parity with the receive path: re-derive height from `blockId` itself rather
      // than trusting the caller's literal `blockHeight` (see `heightOf`'s doc above). Falls back to
      // `blockHeight` only when we can't independently resolve the block (e.g. no `heightOf` wired, as
      // in tests/sim).
      val derivedHeight = heightOf(blockId).getOrElse(blockHeight)
      logger.debug(s"[HotStuff] onLeaderTurn v=$view b=${bid(blockId)} committee=${engine.committee.size} myIndexes=${effects.myVoterIndexes}")
      val proposal = HotStuffProposal(view, blockId, engine.safety.prepareQC)
      effects.broadcast(proposal)
      onProposal(proposal, derivedHeight) // the leader also votes for its own proposal
    }

    def onTimeout(): Unit = {
      val (nextEngine, _) = HotStuffEngine.onTimeout(engine)
      engine = nextEngine
      prunePool() // view advanced on timeout — evict superseded targets (bounded-memory guard)
    }

    def currentView: Int = engine.pacemaker.view

    /** Task 4 watchdog override: replace `engine.safety` with a blank `SafetyState()` -- clearing
      * `lockedQC`, `prepareQC`, and `lastVotedView` -- exactly what a fresh process boot with
      * `initialLockedQC = None` starts from (see this class's `engine` initializer above). Deliberately
      * touches ONLY `engine` (a private `var` of THIS class); there is no `finalizedHeight`,
      * `BlockchainUpdaterImpl`, or any other component reachable from here to touch even if this method
      * had a bug -- see `HotStuffWatchdogFinalizedHeightIsolationSpecification` for the test that
      * verifies this directly rather than merely asserting it in a comment. Also resets the bounded
      * in-flight-repropose tracker (`lastReproposedBlockId`/`reproposeAttempts`) so a stale branch
      * abandoned before the reset doesn't count against the fresh attempt budget after it.
      */
    override def resetLocalSafetyState(): Unit = {
      engine = engine.copy(safety = SafetyState())
      lastReproposedBlockId = None
      reproposeAttempts = 0
      logger.warn("[HotStuff] resetLocalSafetyState: cleared in-memory lockedQC/prepareQC (watchdog-driven recovery)")
    }

    // The classic HotStuff pacemaker liveness optimization (deferred at `blockSource`'s doc comment
    // above and its twin in Application.scala): if this replica already holds a real, quorum-backed QC
    // for a branch that hasn't reached full COMMIT yet, a leader-timeout view-change should re-propose
    // THAT branch -- giving the votes already cast for it a chance to actually finish -- instead of
    // always jumping to whatever `blockSource` would otherwise propose (in production: the current
    // settled tip), which abandons the in-flight round.
    //
    // No new state to track: `SafetyState.prepareQC` (`HotStuffSafety.update`) already records the
    // highest-view QC this replica has seen/formed, regardless of phase, and `EngineState.committedHeight`
    // already advances only on a verified COMMIT-phase QC (`HotStuffEngine.onQC`). So
    // `prepareQC.blockHeight > committedHeight` is exactly "this branch has quorum-backed progress that
    // hasn't finished committing" -- both pieces of data predate this change; the gap was only that
    // `onRoundTimerTick` never consulted them.
    private def inFlightBranch: Option[(BlockId, Int)] =
      engine.safety.prepareQC
        .filter(_.blockHeight.toInt > engine.committedHeight)
        .map(qc => (qc.blockId, qc.blockHeight.toInt))

    def onRoundTimerTick(): Unit = {
      refreshCommittee()
      val stalled = lastTickView.contains(engine.pacemaker.view)
      if (stalled) {
        // No QC advanced the view since the previous tick: the leader for `lastTickView` is presumed
        // silent (crashed/partitioned/slow) -- a genuine leader-timeout, not a heartbeat. Advance the
        // view (same rule bare onTimeout() uses) and, if the deterministic rotation makes THIS replica
        // the new leader, drive the view-change ourselves instead of waiting on an external trigger.
        onTimeout()
        val newView  = engine.pacemaker.view
        val amLeader = effects.myVoterIndexes.exists(idx => HotStuffPacemaker.isLeader(idx, newView, engine.committee))
        if (amLeader) {
          // Prefer re-proposing our own in-flight (QC'd-but-not-committed) branch over `blockSource`'s
          // answer -- see `inFlightBranch`'s doc above -- UNLESS this exact blockId has already been
          // re-proposed `HotStuffCoordinator.Enabled.MaxConsecutiveReproposeAttempts` consecutive times
          // by this replica without resolving: that many identical, unresolved re-proposals is a strong
          // signal the branch can no longer gather quorum (e.g. a mid-round committee change permanently
          // shifted who counts toward it), so this is a genuinely-stuck round, not merely a slow one, and
          // continuing to re-propose it forever would be an unbounded livelock (see field doc above).
          // Falls back to `blockSource()` exactly as before both in the "nothing was in-flight" case
          // (a clean timeout with no prior round in progress) and in this exhausted-retries case.
          val fromInFlight = inFlightBranch
          val exhausted    = fromInFlight.exists { case (blockId, _) =>
            lastReproposedBlockId.contains(blockId) && reproposeAttempts >= HotStuffCoordinator.Enabled.MaxConsecutiveReproposeAttempts
          }
          val effectiveInFlight = if (exhausted) None else fromInFlight
          if (exhausted)
            logger.warn(
              s"[HotStuff] leader-timeout view-change: v=$newView abandoning in-flight b=${bid(fromInFlight.get._1)} after " +
                s"$reproposeAttempts consecutive unresolved re-propose attempts -- falling back to blockSource's fresh tip"
            )
          effectiveInFlight.orElse(blockSource()) match {
            case Some((blockId, blockHeight)) =>
              if (effectiveInFlight.isDefined) {
                // Re-proposing the in-flight branch again: bump (or start) its consecutive-attempt counter.
                reproposeAttempts = if (lastReproposedBlockId.contains(blockId)) reproposeAttempts + 1 else 1
                lastReproposedBlockId = Some(blockId)
              } else {
                // Fell back to blockSource's fresh tip (nothing in-flight, or the in-flight branch was
                // just abandoned above) -- reset the tracker so a future, genuinely-different in-flight
                // branch starts its own attempt count from zero.
                lastReproposedBlockId = None
                reproposeAttempts = 0
              }
              val why = if (effectiveInFlight.isDefined) "RE-proposing in-flight (prepareQC not yet committed)" else "auto-proposing"
              logger.debug(s"[HotStuff] leader-timeout view-change: v=$newView I am the new leader -> $why b=${bid(blockId)}")
              onLeaderTurn(newView, blockId, blockHeight)
            case None =>
              logger.debug(s"[HotStuff] leader-timeout view-change: v=$newView I am the new leader but blockSource has nothing to propose yet")
          }
        }
      }
      lastTickView = Some(engine.pacemaker.view)
    }
  }
}
