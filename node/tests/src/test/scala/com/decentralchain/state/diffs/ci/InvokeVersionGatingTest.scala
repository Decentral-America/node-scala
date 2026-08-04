package com.decentralchain.state.diffs.ci

import com.decentralchain.TestValues.invokeFee
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.lang.directives.values.*
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.state.diffs.FeeValidation.InvokeExtraFeePerStep
import com.decentralchain.test.*
import com.decentralchain.transaction.TxHelpers.*
import com.decentralchain.transaction.TxVersion

/** SC-695 (feature id 30, `BlockchainFeatures.InvokeVersionGating`). See
  * docs/features/feature-30-sc695-spec.md and
  * com.decentralchain.state.diffs.invoke.InvokeVersionGating for the full design.
  *
  * Fast, deterministic, in-process coverage of the version-gating matrix and the
  * `extraFeePerStep` fee mechanism, complementing the (corrected) node-it tests in
  * InvokeScriptTransactionRideV5Suite.
  */
class InvokeVersionGatingTest extends PropSpec with WithDomain {
  import DomainPresets.*

  private def dAppScript(version: StdLibVersion) = {
    val body = if (version == V3) "WriteSet([])" else "nil"
    TestCompiler(version).compileContract(
      s"""
         | @Callable(i)
         | func default() = $body
      """.stripMargin
    )
  }

  // --- Matrix row: tx V1/V2 x dApp STDLIB V5+ -----------------------------------------------

  property("post-activation: V1/V2 invoking a STDLIB V5 dApp is rejected") {
    withDomain(InvokeVersionGating, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      d.appendBlock(setScript(secondSigner, dAppScript(V5)))

      d.appendBlockE(invoke(version = TxVersion.V1)) should produce(
        "Can't invoke a RIDE STDLIB V5 dApp via InvokeScriptTransaction version 1: version 3 is required"
      )
      d.appendBlockE(invoke(version = TxVersion.V2)) should produce(
        "Can't invoke a RIDE STDLIB V5 dApp via InvokeScriptTransaction version 2: version 3 is required"
      )
    }
  }

  property("post-activation: V3 invoking a STDLIB V5 dApp is allowed") {
    withDomain(InvokeVersionGating, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      d.appendBlock(setScript(secondSigner, dAppScript(V5)))
      d.appendBlock(invoke(version = TxVersion.V3))
    }
  }

  property("pre-activation: V1/V2 invoking a STDLIB V5 dApp is byte-identical to today (still allowed)") {
    // Same scenario as the first test above, but on the RideV5 preset -- i.e. feature 30 is
    // NOT activated. Proves the gate makes zero behavior change while dormant.
    withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      d.appendBlock(setScript(secondSigner, dAppScript(V5)))
      d.appendBlock(invoke(version = TxVersion.V1))
      d.appendBlock(invoke(version = TxVersion.V2))
    }
  }

  // --- Matrix row: tx V1/V2 x dApp STDLIB V3/V4 (must stay unchanged, even post-activation) ---

  property("post-activation: V1/V2 invoking STDLIB V3/V4 dApps remain allowed, unchanged") {
    withDomain(InvokeVersionGating, AddrWithBalance.enoughBalances(secondSigner, signer(2))) { d =>
      d.appendBlock(setScript(secondSigner, dAppScript(V3)))
      d.appendBlock(setScript(signer(2), dAppScript(V4)))

      d.appendBlock(invoke(dApp = secondAddress, version = TxVersion.V1))
      d.appendBlock(invoke(dApp = secondAddress, version = TxVersion.V2))
      d.appendBlock(invoke(dApp = signer(2).toAddress, version = TxVersion.V1))
      d.appendBlock(invoke(dApp = signer(2).toAddress, version = TxVersion.V2))
    }
  }

  // --- extraFeePerStep: tx V3 x dApp STDLIB V3/V4 -------------------------------------------
  //
  // Resolution of the SC-695 test-authoring gap (spec's "reading (a)": the original ignored
  // tests 4/5 never actually passed any extraFeePerStep-shaped argument to the invocation, and
  // their name/body were out of sync). These tests correct that: the fee mechanism is realized
  // as a static required fee bump (no new wire field -- see InvokeVersionGating's class doc for
  // why a wire-field design was rejected as too high blast-radius), so "specifying
  // extraFeePerStep" is expressed by paying `invokeFee + InvokeExtraFeePerStep` on the existing
  // `fee` field rather than by a new parameter.

  property("post-activation: V3 invoking a STDLIB V3 dApp requires the extra per-step fee") {
    withDomain(InvokeVersionGating, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      d.appendBlock(setScript(secondSigner, dAppScript(V3)))

      d.appendBlockE(invoke(version = TxVersion.V3, fee = invokeFee)) should produce(
        s"Requires $InvokeExtraFeePerStep extra fee"
      )
      d.appendBlock(invoke(version = TxVersion.V3, fee = invokeFee + InvokeExtraFeePerStep))
    }
  }

  property("post-activation: V3 invoking a STDLIB V4 dApp requires the extra per-step fee") {
    withDomain(InvokeVersionGating, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      d.appendBlock(setScript(secondSigner, dAppScript(V4)))

      d.appendBlockE(invoke(version = TxVersion.V3, fee = invokeFee)) should produce(
        s"Requires $InvokeExtraFeePerStep extra fee"
      )
      d.appendBlock(invoke(version = TxVersion.V3, fee = invokeFee + InvokeExtraFeePerStep))
    }
  }

  property("pre-activation: V3 invoking STDLIB V3/V4 dApps at the plain default fee is byte-identical to today") {
    // Same scenario as the two tests above, but on the RideV5 preset (feature 30 inactive).
    // Proves the fee-composition stage adds zero DCC while dormant.
    withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner, signer(2))) { d =>
      d.appendBlock(setScript(secondSigner, dAppScript(V3)))
      d.appendBlock(setScript(signer(2), dAppScript(V4)))

      d.appendBlock(invoke(dApp = secondAddress, version = TxVersion.V3, fee = invokeFee))
      d.appendBlock(invoke(dApp = signer(2).toAddress, version = TxVersion.V3, fee = invokeFee))
    }
  }
}
