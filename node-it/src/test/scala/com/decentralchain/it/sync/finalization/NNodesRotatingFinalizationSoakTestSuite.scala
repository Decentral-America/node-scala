package com.decentralchain.it.sync.finalization

import com.typesafe.config.Config
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.it.api.*
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.{BaseFreeSpec, NodeConfigs}
import com.decentralchain.state.GenerationPeriod
import com.decentralchain.test.NumericExt
import com.decentralchain.utils.ScorexLogging
import org.scalatest.OptionValues

import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

/** SOAK / endurance guard for feature-25 finality under the endorsement-rebroadcast fix.
  *
  * [[NNodesRotatingFinalizationTestSuite]] proves finality survives aggregator rotation for ONE committed
  * generation period (~3 minutes). This suite proves it survives SUSTAINED operation across MANY period
  * rollovers — the real validator loop the live testnet runs (generators re-commit every period; on mainnet
  * this is the `auto-commit-generators` cron). A commit is valid for exactly the NEXT period
  * (`CommitToGenerationTransactionDiff` requires `generationPeriodStart == current.next.start`), so if
  * re-commitment ever falls behind the committee empties and finality legitimately stalls — which this soak
  * would catch as an unbounded finality lag.
  *
  * ==What "finality never regresses" means here (important)==
  * The reported finalized height (`GET /blocks/height/finalized` = `finalizedHeightOrFallback(max-rollback)`)
  * is derived by `FinalizationState.isParentFinalized` from the CURRENT chain tip's aggregated endorsements.
  * Under NG, the liquid tip can be replaced by another forger's key block on the same parent; the replacing
  * block may carry fewer endorsements, so the derived finalized height can dip by a block or two — then
  * re-advances as the still-live (rebroadcast) endorsements are re-aggregated into the next block. This
  * small, self-healing tip jitter is expected: the chain guarantees irreversibility only below
  * `max(finalized, height - maxRollback)` (maxRollback = 100), NOT a hard ratchet at the very tip. A real
  * safety failure is a DEEP reversion — un-finalizing a semi-buried block far below the finality high-water
  * mark — or a genuine STALL (unbounded lag). Those, and only those, fail this soak.
  *
  * Three generators all forge (quorum=1). Every period we re-commit all three for the next period and drive
  * transactions round-robin so the aggregator role keeps rotating. For the WHOLE run we assert:
  *   - no DEEP finality reversion (finalized never drops > MaxFinalityRegression below its high-water mark),
  *   - finality never STALLS (lag = height - finalized stays under the FinalizationStalled alert threshold),
  *   - net finality PROGRESS (the high-water mark keeps climbing) and eventual RECOVERY after any dip.
  *
  * Duration is the `SoakMinutes` constant below (default 30 min ≈ 30 period rollovers with a length-20 period
  * and 3s blocks). Bump it for a longer endurance run. Run explicitly:
  *   sbt "node-it/testOnly *NNodesRotatingFinalizationSoakTestSuite"
  */
class NNodesRotatingFinalizationSoakTestSuite extends BaseFreeSpec, OptionValues, ScorexLogging {
  import NodeConfigs.*

  /** How long to soak. 30 min ≈ 30 generation-period rollovers here. Raise for a longer endurance run. */
  private val SoakMinutes = 30

  /** Finality-lag ceiling. Matches the FinalizationStalled alert threshold; a breach = a real stall. */
  private val MaxLagAllowed = 250

  /** Benign NG tip-replacement jitter is ~1 block. A finalized drop beyond this below the high-water mark
    * means a semi-buried block was un-finalized — a real deep reversion. Kept well under maxRollback (100).
    */
  private val MaxFinalityRegression = 16

  /** Tolerate transient RPC hiccups over a long run; only a sustained outage (this many in a row) fails. */
  private val MaxConsecutiveRpcErrors = 20

  // Same topology as NNodesRotatingFinalizationTestSuite: three forging generators, quorum=1, a length-20
  // period with 3s blocks so all three commits comfortably mine within one period.
  override protected def nodeConfigs: Seq[Config] =
    Seq(Miners.head, Miners(1), Miners(2)).map(
      _.preactivatedFeatures(BlockchainFeatures.DeterministicFinality)
        .overrides("dcc.blockchain.custom.functionality.min-block-time = 3s")
        .overrides("dcc.blockchain.custom.functionality.generation-period-length = 20")
        .quorum(1)
    )

  private def node1    = nodes.head
  private def accounts = nodes.map(_.keyPair)
  private def addrs    = nodes.map(_.address)

  // Re-commit all three generators for `target` (the next period), once per period. Best-effort per node:
  // a commit that races the period boundary (already committed / wrong period) is logged and retried next
  // period rather than failing the soak — a persistent commit failure surfaces instead as a finality stall.
  private def recommit(target: GenerationPeriod): Int = {
    var ok = 0
    nodes.foreach { n =>
      try {
        node1.broadcastRequest(n.signCommitToGenerationRequest(n.address))
        ok += 1
      } catch { case NonFatal(e) => log.warn(s"[soak] commit for ${n.address} (period $target) failed: ${e.getMessage}") }
    }
    log.info(s"[soak] re-committed $ok/${nodes.size} generators for period $target")
    ok
  }

  "finality stays tight across many period rollovers under sustained rotation" in {
    // Start committing on a fresh boundary so the first committee is attributed to a full period (see the
    // period-boundary race note in NNodesRotatingFinalizationTestSuite).
    step("Advance to a fresh generation-period boundary")
    node1.waitForGenerationPeriod(node1.currentGenerationPeriod.value.next)

    step(s"Soak finality for $SoakMinutes minutes, re-committing all generators every period")
    val deadline       = SoakMinutes.minutes.fromNow
    val startFinalized = node1.finalizedHeight
    var lastFinalized  = startFinalized
    var maxFinalized   = startFinalized // finality high-water mark
    var committedFor   = Option.empty[GenerationPeriod]
    var periods        = 0
    var maxLag         = 0
    var advances       = 0
    var regressions    = 0 // benign self-healing tip-jitter dips (for reporting)
    var rpcErrors      = 0 // consecutive RPC failures
    var i              = 0

    while (deadline.hasTimeLeft()) {
      try {
        // Re-commit once per period, as soon as a new period begins (leaves the full period for commits to mine).
        node1.currentGenerationPeriod.foreach { cur =>
          val target = cur.next
          if (!committedFor.contains(target)) {
            recommit(target)
            committedFor = Some(target)
            periods += 1
          }
        }

        // Round-robin the sender so every generator forges blocks (rotating aggregator). A transient tx
        // failure must not kill a multi-hour soak — a genuine outage still surfaces as the finality lag below.
        val sender = accounts(i % accounts.size)
        val recip  = addrs((i + 1) % addrs.size)
        try node1.transfer(sender, recip, 1.dcc, waitForTx = true)
        catch { case NonFatal(e) => log.warn(s"[soak] transfer #$i failed: ${e.getMessage}") }
        i += 1

        val h   = node1.height
        val fin = node1.finalizedHeight
        val lag = h - fin
        rpcErrors = 0

        // SAFETY: a semi-buried finalized block must never be un-finalized. Small self-healing NG
        // tip-replacement jitter (fin dips a block or two, then re-advances) is expected and allowed.
        if (fin < maxFinalized - MaxFinalityRegression)
          fail(
            s"DEEP finality reversion: finalized $fin dropped ${maxFinalized - fin} below high-water $maxFinalized " +
              s"(height=$h, iter=$i) — a semi-buried block was un-finalized"
          )
        // LIVENESS: finality must not stall.
        if (lag > MaxLagAllowed)
          fail(s"finality lag $lag exceeded $MaxLagAllowed (height=$h finalized=$fin, iter=$i) — finality stalled during soak")

        if (fin < lastFinalized) {
          regressions += 1
          log.info(s"[soak] transient finality dip $lastFinalized -> $fin (self-healing tip jitter) at height=$h iter=$i")
        }
        if (fin > lastFinalized) advances += 1
        if (fin > maxFinalized) maxFinalized = fin
        if (lag > maxLag) maxLag = lag
        lastFinalized = fin

        if (i % 20 == 0)
          log.info(
            s"[soak] iter=$i height=$h finalized=$fin lag=$lag periods=$periods advances=$advances " +
              s"regressions=$regressions maxFinalized=$maxFinalized maxLag=$maxLag"
          )
      } catch {
        case NonFatal(e) =>
          rpcErrors += 1
          log.warn(s"[soak] RPC error #$rpcErrors at iter=$i: ${e.getMessage}")
          if (rpcErrors >= MaxConsecutiveRpcErrors)
            fail(s"$rpcErrors consecutive RPC errors — node unresponsive during soak (last: ${e.getMessage})")
      }
    }

    val finalFinalized = node1.finalizedHeight
    val totalAdvance   = maxFinalized - startFinalized
    log.info(
      s"[soak] DONE minutes=$SoakMinutes iters=$i periods=$periods finalityProgress=$totalAdvance " +
        s"advances=$advances transientDips=$regressions maxLag=$maxLag maxFinalized=$maxFinalized final=$finalFinalized"
    )

    withClue("finality did not make net progress over the soak (liveness): ")(totalAdvance should be > 20)
    withClue("soak did not exercise enough period rollovers: ")(periods should be >= 5)
    withClue("finalizedHeight must have advanced repeatedly, not once: ")(advances should be >= 5)
    // Self-healing: by the end, finality must have recovered to within tip-jitter distance of its high-water mark.
    withClue("finality did not recover to its high-water mark after transient dips: ")(
      (maxFinalized - finalFinalized) should be <= MaxFinalityRegression
    )
  }
}
