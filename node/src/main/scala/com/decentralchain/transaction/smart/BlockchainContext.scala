package com.decentralchain.transaction.smart

import cats.Id
import cats.syntax.semigroup.*
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.Global
import com.decentralchain.lang.directives.DirectiveSet
import com.decentralchain.lang.directives.values.{ContentType, ScriptType, StdLibVersion}
import com.decentralchain.lang.v1.CTX
import com.decentralchain.lang.v1.evaluator.ctx.EvaluationContext
import com.decentralchain.lang.v1.evaluator.ctx.impl.dcc.DccContext
import com.decentralchain.lang.v1.evaluator.ctx.impl.{CryptoContext, PureContext}
import com.decentralchain.lang.v1.traits.Environment
import com.decentralchain.state.*
import monix.eval.Coeval

import java.util

object BlockchainContext {

  type In = DCCEnvironment.In

  // NOTE (fixEcrecover): this cache key previously omitted fixEcrecover entirely (and, until
  // feature 28's removal, fixGroth16 too) -- whichever activation-flag combination populated a
  // cache entry first was served to every later block regardless of the real feature state at
  // that height, a genuine cross-node divergence source (a node that warmed its cache before an
  // activation height kept serving stale behavior after it). fixEcrecover is inherited from
  // upstream Waves (feature 24, EcrecoverFix) -- Waves has this exact same omission in their own
  // code, which doesn't make it safe to leave unfixed here. See
  // CONSENSUS-BUG-INVESTIGATION-REFERENCE.md §5 and docs/consensus-divergences-from-upstream.md.
  private val cache = new util.HashMap[(StdLibVersion, Boolean, Boolean, Boolean, Boolean, DirectiveSet), CTX[Environment]]()

  def build(
      version: StdLibVersion,
      nByte: Byte,
      in: Coeval[Environment.InputEntity],
      h: Coeval[Int],
      blockchain: Blockchain,
      isTokenContext: Boolean,
      isContract: Boolean,
      address: Environment.Tthis,
      txId: ByteStr,
      fixUnicodeFunctions: Boolean,
      useNewPowPrecision: Boolean,
      fixBigScriptField: Boolean,
      fixEcrecover: Boolean
  ): Either[String, EvaluationContext[Environment, Id]] =
    DirectiveSet(
      version,
      ScriptType.isAssetScript(isTokenContext),
      ContentType.isDApp(isContract)
    ).map { ds =>
      val environment = DCCEnvironment(nByte, in, h, blockchain, address, ds, txId)
      build(ds, environment, fixUnicodeFunctions, useNewPowPrecision, fixBigScriptField, fixEcrecover)
    }

  def build(
      ds: DirectiveSet,
      environment: Environment[Id],
      fixUnicodeFunctions: Boolean,
      useNewPowPrecision: Boolean,
      fixBigScriptField: Boolean,
      fixEcrecover: Boolean
  ): EvaluationContext[Environment, Id] =
    cache
      .synchronized(
        cache.computeIfAbsent(
          (ds.stdLibVersion, fixUnicodeFunctions, useNewPowPrecision, fixBigScriptField, fixEcrecover, ds),
          { _ =>
            PureContext.build(ds.stdLibVersion, useNewPowPrecision).withEnvironment[Environment] |+|
              CryptoContext.build(Global, ds.stdLibVersion, fixEcrecover).withEnvironment[Environment] |+|
              DccContext.build(Global, ds, fixBigScriptField)
          }
        )
      )
      .evaluationContext(environment)
}
