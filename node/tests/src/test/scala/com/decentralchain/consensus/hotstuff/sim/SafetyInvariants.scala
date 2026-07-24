package com.decentralchain.consensus.hotstuff.sim

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

  def checkAll(commits: Seq[CommitObservation]): Either[String, Unit] =
    for {
      _ <- noFork(commits)
      _ <- noRegression(commits)
    } yield ()
}
