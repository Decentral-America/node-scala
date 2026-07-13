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
      // Safety guard for HotStuff-over-FairPoS: is `blockId` THE canonical block at height `view`?
      // A replica votes only for a proposal that matches its own settled chain, so a Byzantine leader
      // cannot make honest nodes vote for a fabricated block. Default permissive for the in-memory sim.
      proposalValid: (Int, BlockId) => Boolean = (_, _) => true
  ) extends HotStuffCoordinator
      with StrictLogging {
    private var engine = EngineState(committeeProvider())
    private var pool   = VotePool()
    private var voted  = Set.empty[(Int, HotStuffPhase, BlockId)] // per-target vote guard (prevents storms/loops)

    // The committed-generator committee rotates per generation period; refresh it from the chain at
    // the start of each event so reducers always see the current period's set.
    private def refreshCommittee(): Unit = engine = engine.copy(committee = committeeProvider())

    private def bid(b: BlockId): String = b.toString.take(8)

    /** Cast this node's vote(s) for a target exactly once, then feed our own vote into our pool. */
    private def castVotes(view: Int, phase: HotStuffPhase, blockId: BlockId, height: Int): Unit = {
      val key = (view, phase, blockId)
      if (!voted.contains(key)) {
        voted += key
        val message = HotStuffQuorum.voteMessage(view, phase, blockId, height)
        val mine    = effects.myVoterIndexes
        logger.info(s"[HotStuff] castVotes $phase v=$view b=${bid(blockId)} myIndexes=$mine committee=${engine.committee.size}")
        mine.foreach { idx =>
          effects.signVote(message, idx) match {
            case Some(sig) =>
              val vote = HotStuffVote(view, phase, blockId, Height(height), idx, sig.byteStr)
              effects.broadcast(vote)
              onVote(vote) // count our own vote locally
            case None => logger.warn(s"[HotStuff] signVote returned None for idx=$idx (no BLS key for this committee slot?)")
          }
        }
      }
    }

    def onProposal(proposal: HotStuffProposal, blockHeight: Int): Unit = {
      refreshCommittee()
      if (!proposalValid(proposal.view, proposal.blockId)) {
        logger.info(s"[HotStuff] onProposal v=${proposal.view} b=${bid(proposal.blockId)} REJECTED (not the canonical block at this view)")
      } else {
        val (nextEngine, shouldVote) = HotStuffEngine.onProposal(engine, proposal, extendsBranch)
        engine = nextEngine
        logger.info(s"[HotStuff] onProposal v=${proposal.view} b=${bid(proposal.blockId)} shouldVote=$shouldVote committee=${engine.committee.size}")
        if (shouldVote) castVotes(proposal.view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, proposal.blockId, blockHeight)
      }
    }

    def onVote(vote: HotStuffVote): Unit = {
      refreshCommittee()
      val (nextPool, maybeQC) = HotStuffVotePool.onVote(pool, vote, engine.committee)
      pool = nextPool
      // Pool-level instrumentation: the accumulated distinct signers + whether they clear the 2/3
      // stake quorum, per target. When the bucket clears (QC formed) it is empty here => QC=true tells
      // the story. This is the datum that distinguishes "votes don't co-reside / committee mismatch"
      // (pooledVoters stays small) from a formation bug (quorum met yet QC=false).
      val key    = (vote.view, vote.phase, vote.blockId)
      val bucket = nextPool.pending.getOrElse(key, Vector.empty)
      val voters = bucket.map(_.voterIndex).distinct.sorted
      val quorum = HotStuffQuorum.hasQuorum(voters, engine.committee)
      logger.info(
        s"[HotStuff] onVote from #${vote.voterIndex} ${vote.phase} v=${vote.view} b=${bid(vote.blockId)} pooledVoters=$voters quorumByStake=$quorum -> QC=${maybeQC.isDefined}"
      )
      // Should be unreachable now that all votes carry the settled-view blockHeight, but if it ever
      // fires it means the bucket reached quorum yet formQC rejected it — surface WHY loudly (do not
      // let a QC-formation failure hide as it did before the blockHeight fix).
      if (maybeQC.isEmpty && quorum)
        logger.warn(
          s"[HotStuff] QUORUM REACHED but no QC v=${vote.view} ${vote.phase} b=${bid(vote.blockId)} " +
            s"— votes disagree on blockHeight=${bucket.map(_.blockHeight.toInt).distinct.sorted} (must be identical to form a QC)"
        )
      maybeQC.foreach { qc =>
        effects.broadcast(qc)
        onQC(qc)
      }
    }

    def onQC(qc: QuorumCertificate): Unit = {
      refreshCommittee()
      val (nextEngine, actions) = HotStuffEngine.onQC(engine, qc)
      engine = nextEngine
      val rejected = actions.exists { case _: HotStuffAction.Rejected => true; case _ => false }
      logger.info(
        s"[HotStuff] onQC ${qc.phase} v=${qc.view} b=${bid(qc.blockId)} signers=${qc.signerIndexes.size} rejected=$rejected actions=${actions.mkString(",")}"
      )
      actions.foreach {
        case HotStuffAction.Committed(blockId, height) => effects.onCommit(blockId, height)
        case _                                         => ()
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
    }

    def onLeaderTurn(view: Int, blockId: BlockId, blockHeight: Int): Unit = {
      refreshCommittee()
      logger.info(s"[HotStuff] onLeaderTurn v=$view b=${bid(blockId)} committee=${engine.committee.size} myIndexes=${effects.myVoterIndexes}")
      val proposal = HotStuffProposal(view, blockId, engine.safety.prepareQC)
      effects.broadcast(proposal)
      onProposal(proposal, blockHeight) // the leader also votes for its own proposal
    }

    def onTimeout(): Unit = {
      val (nextEngine, _) = HotStuffEngine.onTimeout(engine)
      engine = nextEngine
    }
  }
}
