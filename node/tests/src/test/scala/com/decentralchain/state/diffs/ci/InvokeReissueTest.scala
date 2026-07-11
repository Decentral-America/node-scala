package com.decentralchain.state.diffs.ci
import com.decentralchain.TestValues.invokeFee
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.lang.directives.values.*
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.TxHelpers.*

class InvokeReissueTest extends PropSpec with WithDomain {
  import DomainPresets.*

  property("invoke fails on reissue of foreign asset") {
    withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      val issueTx = issue()
      val asset   = IssuedAsset(issueTx.id())
      val dApp    = TestCompiler(V5).compileContract(
        s"""
           | @Callable(i)
           | func default() = [
           |   Reissue(base58'$asset', 1, true)
           | ]
        """.stripMargin
      )
      d.appendBlock(issueTx)
      d.appendBlock(setScript(secondSigner, dApp))
      d.appendAndAssertFailed(invoke(), "Asset was issued by other address")
    }
  }

  property("set reissuable = false and try to reissue via invoke") {
    withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      val issueTx = issue(secondSigner)
      val asset   = IssuedAsset(issueTx.id())
      val dApp    = TestCompiler(V5).compileContract(
        s"""
           | @Callable(i)
           | func default() =
           |   [
           |     Reissue(base58'$asset', 1, false)
           |   ]
         """.stripMargin
      )
      d.appendBlock(setScript(secondSigner, dApp), issueTx)
      d.appendAndAssertSucceed(invoke())
      d.appendAndAssertFailed(invoke(), "Asset is not reissuable")
    }
  }

  property("Reissue transaction for asset issued via invoke") {
    withDomain(RideV5, AddrWithBalance.enoughBalances(secondSigner)) { d =>
      val dApp = TestCompiler(V5).compileContract(
        s"""
           | @Callable(i)
           | func default() =
           |   [
           |     Issue("name", "description", 1000, 4, true, unit, 0)
           |   ]
         """.stripMargin
      )
      d.appendBlock(setScript(secondSigner, dApp))
      d.appendBlock(invoke(fee = invokeFee(issues = 1)))
      val asset = d.liquidSnapshot.assetStatics.head._1
      d.appendBlock(reissue(asset, secondSigner, 234))
      d.blockchain.assetDescription(asset).get.totalVolume shouldBe 1234
    }
  }
}
