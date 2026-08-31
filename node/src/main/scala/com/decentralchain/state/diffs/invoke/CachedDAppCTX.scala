package com.decentralchain.state.diffs.invoke

import cats.syntax.semigroup.*
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.features.BlockchainFeatures.{ConsensusImprovements, EcrecoverFix, SynchronousCalls}
import com.decentralchain.lang.Global
import com.decentralchain.lang.directives.values.{Account, DApp, StdLibVersion, V3}
import com.decentralchain.lang.directives.{DirectiveDictionary, DirectiveSet}
import com.decentralchain.lang.v1.evaluator.ctx.InvariableContext
import com.decentralchain.lang.v1.evaluator.ctx.impl.dcc.DccContext
import com.decentralchain.lang.v1.evaluator.ctx.impl.{CryptoContext, PureContext}
import com.decentralchain.lang.v1.traits.Environment
import com.decentralchain.state.Blockchain

object CachedDAppCTX {
  private val cache: Map[(StdLibVersion, Boolean, Boolean, Boolean), InvariableContext] =
    (for {
      version            <- DirectiveDictionary[StdLibVersion].all.filter(_ >= V3)
      useNewPowPrecision <- Seq(true, false)
      fixBigScriptField  <- Seq(true, false)
      fixEcrecover       <- Seq(true, false)
    } yield {
      val ctx = PureContext.build(version, useNewPowPrecision).withEnvironment[Environment] |+|
        CryptoContext.build(Global, version, fixEcrecover).withEnvironment[Environment] |+|
        DccContext.build(Global, DirectiveSet(version, Account, DApp).explicitGet(), fixBigScriptField)
      ((version, useNewPowPrecision, fixBigScriptField, fixEcrecover), InvariableContext(ctx))
    }).toMap

  def get(version: StdLibVersion, b: Blockchain): InvariableContext =
    cache(
      (
        version,
        b.isFeatureActivated(SynchronousCalls) && b.height > b.settings.functionalitySettings.enforceTransferValidationAfter,
        b.isFeatureActivated(ConsensusImprovements),
        b.isFeatureActivated(EcrecoverFix)
      )
    )
}
