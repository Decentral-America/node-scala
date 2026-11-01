package com.decentralchain.it.sync.finalization

import com.decentralchain.api.http.requests.CommitToGenerationRequest
import com.decentralchain.it.api.*
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.{BaseFreeSpec, Node}
import com.decentralchain.utils.ScorexLogging
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.OptionValues.*

/** The commit-to-generation handshake hardened for loaded CI runners, extracted verbatim from
  * HotStuffFourNodeSuite (FourNodeHotStuffTestSuite.scala) so the TwoNodes finalization suite stops racing the generation-period cutoff
  * with fire-and-forget broadcasts (nightly node-it red since 2026-07-27:
  * TwoNodesFinalizationTestSuite.scala:48 `0 was not equal to 2` — commits missed the period).
  *
  * Three CI-observed races handled (same as the FourNode original):
  *  1. sender already committed → skipped up front via the generators(period.start) read;
  *  2. "already in the state" 400 on broadcast → benign concurrent inclusion, skip;
  *  3. "Expected the next period start height" 400 → the period rolled over mid-batch;
  *     retry the whole selection against the fresh period (bounded by retriesLeft).
  */
trait HotStuffCommitOps extends ScorexLogging { self: BaseFreeSpec =>

  /** (node that signs and broadcasts, sender address to commit). */
  protected def commitTargets: Seq[(Node, String)]
  protected def commitLeader: Node

  /** Commit every not-yet-committed (node, sender) for the next period and wait until that period is
    * active. Returns the committed generators (address + generating balance) for that period.
    *
    * Idempotent and race-safe against two distinct sources of "already in the state" rejections that
    * both used to fail the whole test outright:
    *
    *   1. Across "it" blocks: this suite reuses the same live containers for all its tests (no
    *      reset in between), and a CommitToGenerationTransaction is deterministic per (sender,
    *      generationPeriodStart) -- re-signing produces a byte-identical transaction. If a PRIOR "it"
    *      block already committed everyone for this same upcoming period (plausible: committing itself
    *      takes far less time than a full period, so the "next" period computed here can be the SAME
    *      one an earlier block already secured), re-broadcasting would resubmit an already-included
    *      transaction. Fixed by checking who's already committed (via /generators/at) before signing.
    *
    *   2. WITHIN one call, on a real P2P-connected cluster: broadcasting every commit to every
    *      node (as this originally did) is redundant -- one broadcast is enough for gossip to reach the
    *      whole cluster -- and that redundancy created a genuine race: by the time a LATER redundant
    *      broadcast of the SAME transaction reached some node, gossip from an EARLIER broadcast (to a
    *      different node) had often already gotten that transaction included in a block, so the node
    *      correctly rejected the redundant copy. Fixed by broadcasting each commit exactly once, from
    *      its own signing node, and treating a same-transaction "already in the state" rejection on
    *      that single broadcast as benign (the transaction ending up included via some other path is
    *      exactly the outcome we want) rather than a real failure.
    *
    *   3. Across the WHOLE call, on a loaded CI runner: `period` is computed once up front, but signing
    *      and broadcasting one commit per not-yet-committed node is real wall-clock time. If that adds up
    *      to a full period length before the last node's broadcast lands, the chain has already rolled
    *      over to the period after the one this call targeted, and `CommitToGenerationTransactionDiff`
    *      correctly rejects the now-stale `generationPeriodStart` with "Expected the next period start
    *      height (X), got Y" -- a real, observed 400 on CI, distinct from both races above (it's neither
    *      an already-committed sender nor an already-included transaction). Fixed by treating that
    *      specific rejection as a signal to retry the whole selection against the freshly-current period
    *      rather than a failure: any node that already got included under the old period will show up in
    *      the next attempt's `alreadyCommitted` and won't be resubmitted.
    *
    * Retry audit (2026-10-31, ECO-98). The bounded retry was added in 1474ef9b13 (2026-07-27) after a real
    * nightly failure ("Expected the next period start height (7), got 4") and moved here unchanged in
    * ebc3712b3a. It does not mask a node race:
    *   - the race is between this test client and chain progress (period read, then sign + broadcast), not
    *     inside the node; the node's rejection is the correct consensus rule
    *     (CommitToGenerationTransactionDiff: a commitment must target the next period start);
    *   - it cannot be removed by the client: any read-then-submit against a live chain with 3-block periods
    *     can straddle a boundary, so the only alternatives are stopping the chain or retrying;
    *   - only that exact rejection is retried; every other error fails the test, the retry count is bounded
    *     (`require` fails loudly), and each retry re-reads `alreadyCommitted`, so no transaction is resubmitted
    *     and the returned committee is read back from the chain (`generators(period.start)`), which the
    *     callers assert on.
    * The historic red runs (38093314010, 38151643329) did not go through this retry: they were a miner
    * wedge on a committee change (fixed in the node by #74, 3bcf076dfe) and a fault-injection test that
    * stopped the next period's only committee member (fixed by #75, d1951aa79d, `commitForUpcomingPeriod`).
    */
  protected def commitAllForNextPeriod(retriesLeft: Int = 2): Seq[GeneratorsResponse.Entry] = {
    val period = commitLeader.currentGenerationPeriod.value.next
    // /generators/at/{h} 404s until the chain has actually reached height h (the route's own guard is
    // period-based and would allow it, but the underlying height genuinely doesn't exist yet on the
    // FIRST call in the suite, before anyone has committed or waited for anything) -- a 404 here means
    // "nothing possibly committed there yet" exactly as much as an empty list would, so treat it the same.
    val alreadyCommitted =
      try commitLeader.generators(period.start).map(_.address).toSet
      catch {
        case ApiCallException(e: UnexpectedStatusCodeException) if e.statusCode == StatusCodes.NotFound.intValue =>
          Set.empty[String]
      }
    val toCommit         = commitTargets.filterNot { case (_, sender) => alreadyCommitted.contains(sender) }
    val periodRolledOver = toCommit.exists { case (node, sender) =>
      val commit = node.sign(CommitToGenerationRequest(sender = Some(sender)))
      try {
        node.broadcastRequest(commit)
        false
      } catch {
        case ApiCallException(e: UnexpectedStatusCodeException)
            if e.statusCode == StatusCodes.BadRequest.intValue && e.responseBody.contains("already in the state") =>
          log.info(
            s"CommitToGeneration for $sender raced with a concurrent inclusion (benign, transaction ${commit.id} is in the state): skipping"
          )
          false
        case ApiCallException(e: UnexpectedStatusCodeException)
            if e.statusCode == StatusCodes.BadRequest.intValue && e.responseBody.contains("Expected the next period start height") =>
          log.info(
            s"CommitToGeneration for $sender targeted period ${period.start}, which already rolled over while committing the batch: retrying"
          )
          true
      }
    }
    if (periodRolledOver) {
      require(retriesLeft > 0, s"commitAllForNextPeriod: generation period kept rolling over past all retries")
      commitAllForNextPeriod(retriesLeft - 1)
    } else {
      commitTargets.map(_._1).distinct.foreach(_.waitForGenerationPeriod(period))
      val committed = commitLeader.generators(period.start)
      // Broadcasting accepted every commit, but a commit that is not mined before the period starts never makes
      // it into that period's committee (periods are 3 blocks here, so on a loaded runner the second commit can
      // miss the window; node-it run 41346961774 returned 1 of 2). Verify, and retry against the next period.
      val missing = commitTargets.map(_._2).toSet -- committed.map(_.address)
      if (missing.nonEmpty) {
        require(
          retriesLeft > 0,
          s"commitAllForNextPeriod: ${missing.mkString(", ")} still missing from period ${period.start} after all retries"
        )
        log.info(s"CommitToGeneration for ${missing.mkString(", ")} was not included before period ${period.start} began: retrying")
        commitAllForNextPeriod(retriesLeft - 1)
      } else committed
    }
  }

  /** Best-effort: commit `targets` for the period after the current one, without waiting for it.
    *
    * Fault-injection tests take a committee member down for longer than one generation period
    * (3 blocks in these suites). The following period's committee is otherwise only whoever
    * self-committed while forging, which can be just the member that was taken down: then nobody
    * left is allowed to generate ("X is not allowed to generate a block, allowed: <victim>") and the
    * chain correctly halts, failing the test for a reason it does not test. Rejections here (already
    * committed, the period rolled over mid-call) are expected and ignored; the next call retries.
    */
  protected def commitForUpcomingPeriod(targets: Seq[(Node, String)]): Unit =
    targets.foreach { case (node, sender) =>
      try node.broadcastRequest(node.sign(CommitToGenerationRequest(sender = Some(sender))))
      catch {
        case ApiCallException(e: UnexpectedStatusCodeException) if e.statusCode == StatusCodes.BadRequest.intValue =>
          log.debug(s"commitForUpcomingPeriod($sender): ${e.responseBody}")
      }
    }
}
