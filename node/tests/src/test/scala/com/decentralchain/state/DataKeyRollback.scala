package com.decentralchain.state

import com.decentralchain.db.WithState
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.lang.directives.values.V7
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.settings.DCCSettings
import com.decentralchain.test.*
import com.decentralchain.transaction.TxHelpers

class DataKeyRollback extends PropSpec with SharedDomain {
  private val richAccount = TxHelpers.signer(1500)

  override def genesisBalances: Seq[WithState.AddrWithBalance] = Seq(AddrWithBalance(richAccount.toAddress, 10_000_000.dcc))
  override def settings: DCCSettings                           = DomainPresets.TransactionStateSnapshot

  property("check new entries") {
    val oracleAccount = TxHelpers.signer(1501)
    val dappAccount   = TxHelpers.signer(1502)

    val dataSenderCount = 5
    val dataEntryCount  = 5

    val dataSenders = IndexedSeq.tabulate(dataSenderCount)(i => TxHelpers.signer(1550 + i))
    domain.appendBlock(
      TxHelpers
        .massTransfer(
          richAccount,
          dataSenders.map(kp => kp.toAddress -> 100.dcc) ++
            Seq(oracleAccount.toAddress -> 100.dcc, dappAccount.toAddress -> 10.dcc),
          fee = 0.05.dcc
        ),
      TxHelpers.setScript(
        dappAccount,
        TestCompiler(V7).compileContract(s"""
          let oracleAddress = Address(base58'${oracleAccount.toAddress}')
          @Callable(i)
          func default() = [
            IntegerEntry("loadedHeight_" + height.toString() + i.transactionId.toBase58String(), oracleAddress.getIntegerValue("lastUpdatedBlock"))
          ]
        """)
      ),
      TxHelpers.data(oracleAccount, Seq(IntegerDataEntry("lastUpdatedBlock", 2)))
    )
    domain.appendBlock(dataSenders.map(kp => TxHelpers.data(kp, Seq.tabulate(dataEntryCount)(i => IntegerDataEntry("kv_" + i, 501)), 0.01.dcc))*)
    domain.appendBlock(dataSenders.map(kp => TxHelpers.data(kp, Seq.tabulate(dataEntryCount)(i => IntegerDataEntry("kv_" + i, 503)), 0.01.dcc))*)
    domain.appendBlock(
      (dataSenders.map(kp => TxHelpers.data(kp, Seq.tabulate(dataEntryCount)(i => IntegerDataEntry("kv_" + i, 504)), 0.01.dcc)) ++
        Seq(
          TxHelpers.invoke(dappAccount.toAddress, invoker = richAccount),
          TxHelpers.data(oracleAccount, Seq(IntegerDataEntry("lastUpdatedBlock", 5)))
        ))*
    )
    domain.appendBlock()
    val discardedBlocks = domain.rollbackTo(domain.blockchain.blockId(domain.blockchain.height - 2).get)
    discardedBlocks.foreach { x =>
      domain.appendBlock(x.block)
    }
  }
}
