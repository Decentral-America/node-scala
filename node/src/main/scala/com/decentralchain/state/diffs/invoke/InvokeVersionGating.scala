package com.decentralchain.state.diffs.invoke

import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.lang.directives.values.{StdLibVersion, V3, V4, V5}
import com.decentralchain.lang.script.ContractScript.ContractScriptImpl
import com.decentralchain.state.{AccountScriptInfo, Blockchain}
import com.decentralchain.transaction.TxVersion
import com.decentralchain.transaction.smart.InvokeScriptTransaction

/** SC-695 (feature id 30, `BlockchainFeatures.InvokeVersionGating`).
  *
  * Everything in this object is a no-op (returns "old" behavior: no rejection, zero extra fee)
  * unless `BlockchainFeatures.InvokeVersionGating` is activated on `blockchain`. See
  * `docs/features/feature-30-sc695-spec.md` for the full design rationale.
  *
  * Version-compatibility matrix (only enforced once the feature is activated):
  *   - InvokeScriptTransaction V1/V2 invoking a dApp whose deployed script is STDLIB V5+
  *     (dApp-to-dApp synchronous calls) -> REJECTED. V5's invocation model needs the wire data
  *     V3 carries (attached-payments list, tx.fee/tx.feeAssetId propagation to nested calls);
  *     V1/V2 cannot carry that, so the combination is disallowed outright.
  *   - InvokeScriptTransaction V1/V2 invoking a STDLIB V3/V4 dApp -> allowed, unchanged.
  *   - InvokeScriptTransaction V3 invoking a STDLIB V5+ dApp -> allowed, unchanged (this is the
  *     combination V3 exists to support).
  *   - InvokeScriptTransaction V3 invoking a STDLIB V3/V4 dApp -> allowed, but requires a static
  *     per-step extra fee (see `extraFeeSteps`) on top of the normal InvokeScriptTransaction min
  *     fee. Rationale: V3/V4 dApps can never make dApp-to-dApp synchronous calls (that requires
  *     V5+), so there is no genuine per-call-depth cost to bill for pre-execution; the "step" is
  *     therefore a fixed constant of 1 for this specific combination, not a dynamic count. A true
  *     dynamic per-call-depth fee (keyed on `ContractLimits.MaxSyncDAppCalls`/
  *     `DAppEnvironment.InvocationTreeTracker`) would need two-phase (pre + post-execution)
  *     billing that this change deliberately does NOT attempt -- see feature-30-sc695-spec.md's
  *     "Recommendation" section and this feature's implementation report for why that was
  *     deferred as a separate, riskier change.
  */
object InvokeVersionGating {

  /** The deployed STDLIB version of the script at the invoked dApp address/alias, if any. */
  def dAppStdLibVersion(blockchain: Blockchain, tx: InvokeScriptTransaction): Option[StdLibVersion] =
    blockchain
      .resolveAlias(tx.dApp)
      .toOption
      .flatMap(blockchain.accountScript)
      .collect { case AccountScriptInfo(_, ContractScriptImpl(version, _), _, _) => version }

  /** True iff, feature active, this invocation must be rejected outright (matrix row V1/V2 x V5+). */
  def rejectsInvocation(blockchain: Blockchain, tx: InvokeScriptTransaction): Boolean =
    blockchain.isFeatureActivated(BlockchainFeatures.InvokeVersionGating) &&
      tx.version < TxVersion.V3 &&
      dAppStdLibVersion(blockchain, tx).exists(_ >= V5)

  /** Fixed per-invocation step count charged by `extraFeeSteps` for the combination that requires
    * it. NOT a dynamic call-depth count -- see class doc.
    */
  val StaticExtraFeeSteps: Int = 1

  /** Number of billable "steps" (see `FeeValidation.InvokeExtraFeePerStep`) required for this
    * invocation, feature active. Zero when the feature is inactive or the invocation doesn't
    * match the one billed combination (V3 tx x STDLIB V3/V4 dApp).
    */
  def extraFeeSteps(blockchain: Blockchain, tx: InvokeScriptTransaction): Int =
    if (!blockchain.isFeatureActivated(BlockchainFeatures.InvokeVersionGating)) 0
    else if (tx.version == TxVersion.V3 && dAppStdLibVersion(blockchain, tx).exists(v => v == V3 || v == V4)) StaticExtraFeeSteps
    else 0

  def rejectionMessage(blockchain: Blockchain, tx: InvokeScriptTransaction): String = {
    val v = dAppStdLibVersion(blockchain, tx).map(_.toString).getOrElse("unknown")
    s"Can't invoke a RIDE STDLIB $v dApp via InvokeScriptTransaction version ${tx.version}: version 3 is required"
  }
}
