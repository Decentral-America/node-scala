package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.consensus.hotstuff.HotStuffSafety

/** Post-run SAFETY checks over a [[DstHarness]]'s recorded commits. Deliberately scoped to safety
  * (never two conflicting values agreed, never regresses), not liveness (whether/how fast progress is
  * made) — mirroring the same deliberate scope restriction already documented in node-it's
  * `FourNodeHotStuffTestSuite`.
  */
object SafetyInvariants {

  /** No two nodes ever commit different blockIds at the same height. */
  def noFork(commits: Seq[CommitObservation]): Either[String, Unit] =
    commits.groupBy(_.height).foldLeft[Either[String, Unit]](Right(())) {
      case (Right(()), (height, obs)) =>
        val distinctBlocks = obs.map(_.blockId).distinct
        if (distinctBlocks.size > 1)
          Left(s"FORK at height $height: conflicting blocks $distinctBlocks (observations: $obs)")
        else Right(())
      case (left, _) => left
    }

  /** Per node, committed height must never regress across the run (observations are in delivery
    * order within the sequence, since [[DstHarness]] appends to `commits` as `onCommit` fires).
    */
  def noRegression(commits: Seq[CommitObservation]): Either[String, Unit] =
    commits.groupBy(_.node).foldLeft[Either[String, Unit]](Right(())) {
      case (Right(()), (node, obs)) =>
        val heights = obs.map(_.height)
        heights.sliding(2).collectFirst { case Seq(a, b) if b < a => (a, b) } match {
          case Some((a, b)) => Left(s"REGRESSION on node $node: committed height went from $a to $b")
          case None         => Right(())
        }
      case (left, _) => left
    }

  /** No node ever signs two DIFFERENT blocks at the same `(view, phase)`.
    *
    * Audit F-2 (HIGH, 2026-08-31): `checkAll` previously inspected only committed heights, so it could
    * not have detected an equivocation even in a scenario that produced one -- and the watchdog's own
    * `resetLocalSafetyState()` recovery path DID produce one, by clearing `lastVotedView` (the sole
    * anti-double-vote bound) along with the lock. Every node in these simulations is honest by
    * construction (they all run unmodified production `HotStuffCoordinator.Enabled` code), so ANY
    * equivocation signature here is by definition a self-inflicted protocol violation, not Byzantine
    * behaviour the harness injected. Delegates to the production detector
    * (`HotStuffSafety.equivocators`) rather than reimplementing the rule, so this asserts on exactly
    * what production would flag.
    */
  def noEquivocation(votes: Seq[VoteObservation]): Either[String, Unit] = {
    val offenders = HotStuffSafety.equivocators(votes.map(_.vote))
    if (offenders.isEmpty) Right(())
    else {
      val detail = votes
        .filter(o => offenders.contains(o.vote.voterIndex))
        .groupBy(o => (o.vote.voterIndex, o.vote.view, o.vote.phase))
        .collect { case (k, os) if os.map(_.vote.blockId).distinct.size > 1 => s"$k -> ${os.map(_.vote.blockId).distinct}" }
        .mkString("; ")
      Left(s"EQUIVOCATION by voter(s) $offenders: $detail")
    }
  }

  def checkAll(commits: Seq[CommitObservation]): Either[String, Unit] =
    for {
      _ <- noFork(commits)
      _ <- noRegression(commits)
    } yield ()

  /** Full safety sweep including the vote-level equivocation check. Separate from the commits-only
    * [[checkAll]] so existing scenarios keep their exact prior signature/behaviour.
    */
  def checkAll(commits: Seq[CommitObservation], votes: Seq[VoteObservation]): Either[String, Unit] =
    for {
      _ <- checkAll(commits)
      _ <- noEquivocation(votes)
    } yield ()
}
