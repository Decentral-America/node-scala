package com.decentralchain.state.diffs

import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.lang.directives.values.V6
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.test.{PropSpec, produce}
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.TxHelpers.*
import com.decentralchain.transaction.TxVersion.*
import com.decentralchain.transaction.assets.exchange.OrderType.*
import com.decentralchain.transaction.{Transaction, TxVersion}

class TransactionVersionValidationTest extends PropSpec with WithDomain {
  private val script   = TestCompiler(V6).compileExpression("true")
  private val issueTx1 = issue(script = Some(script))
  private val issueTx2 = issue()
  private val leaseTx  = lease()
  private val setDApp  = setScript(
    secondSigner,
    TestCompiler(V6).compileContract(
      """
        | @Callable(i)
        | func default() = []
    """.stripMargin
    )
  )
  private val preconditions = Seq(issueTx1, issueTx2, leaseTx, setDApp)

  private val asset  = IssuedAsset(issueTx1.id())
  private val asset2 = IssuedAsset(issueTx2.id())
  private def order1 = order(BUY, asset, Dcc, price = 123456789, version = V1)
  private def order2 = order(SELL, asset, Dcc, price = 123456789, version = V1)

  private def txsByMaxVersion: Seq[(TxVersion, TxVersion => Transaction)] =
    Seq(
      (V3, v => transfer(version = v)),
      (V3, v => issue(version = v)),
      (V3, v => reissue(asset, version = v)),
      (V3, v => burn(asset, version = v)),
      (V3, v => lease(version = v)),
      (V3, v => leaseCancel(leaseTx.id(), version = v)),
      (V3, v => createAlias("alias", version = v)),
      (V3, v => exchange(order1, order2, version = v)),
      (V2, v => data(defaultSigner, Seq(), version = v)),
      (V2, v => invoke(version = v)),
      (V2, v => massTransfer(version = v, fee = 200_000)),
      (V2, v => setAssetScript(defaultSigner, asset, script, version = v)),
      (V2, v => setScript(defaultSigner, script, version = v)),
      (V2, v => sponsor(asset2, version = v)),
      (V1, v => updateAssetInfo(asset.id, version = v))
    )

  property("zero and negative tx versions are forbidden before and after snapshot activation") {
    Seq(BlockRewardDistribution, TransactionStateSnapshot).foreach(settings =>
      withDomain(
        settings.configure(_.copy(minAssetInfoUpdateInterval = 1)),
        AddrWithBalance.enoughBalances(defaultSigner, secondSigner)
      ) { d =>
        d.appendBlock(preconditions*)
        txsByMaxVersion.foreach { case (_, tx) =>
          d.appendBlockE(tx(0: Byte)) should produce("Bad transaction")
          d.appendBlockE(tx(-1: Byte)) should produce("Bad transaction")
        }
      }
    )
  }

  property("max tx version is available before and after snapshot activation") {
    Seq(BlockRewardDistribution, TransactionStateSnapshot).foreach(settings =>
      withDomain(
        settings.configure(_.copy(minAssetInfoUpdateInterval = 1)),
        AddrWithBalance.enoughBalances(defaultSigner, secondSigner)
      ) { d =>
        d.appendBlock(preconditions*)
        d.appendAndAssertSucceed(txsByMaxVersion.map { case (maxVersion, tx) => tx(maxVersion) }*)
      }
    )
  }

  property("more than max tx version is available before snapshot activation") {
    withDomain(
      BlockRewardDistribution.configure(_.copy(minAssetInfoUpdateInterval = 1)),
      AddrWithBalance.enoughBalances(defaultSigner, secondSigner)
    ) { d =>
      d.appendBlock(preconditions*)
      d.appendAndAssertSucceed(txsByMaxVersion.map { case (maxVersion, tx) => tx((maxVersion + 1).toByte) }*)
    }
  }

  property("more than max tx version is forbidden after snapshot activation") {
    withDomain(
      TransactionStateSnapshot.configure(_.copy(minAssetInfoUpdateInterval = 1)),
      AddrWithBalance.enoughBalances(defaultSigner, secondSigner)
    ) { d =>
      d.appendBlock(preconditions*)
      txsByMaxVersion.foreach { case (maxVersion, tx) =>
        d.appendBlockE(tx((maxVersion + 1).toByte)) should produce("Bad transaction")
      }
    }
  }
}
