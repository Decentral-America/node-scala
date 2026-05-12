package com.decentralchain

import com.google.common.primitives.Longs
import com.decentralchain.common.state.ByteStr
import com.decentralchain.settings.WalletSettings
import com.decentralchain.wallet.Wallet

trait TestWallet {
  protected val testWallet: Wallet = TestWallet.instance
}

object TestWallet {
  private[TestWallet] lazy val instance = Wallet(WalletSettings(None, Some("123"), Some(ByteStr(Longs.toByteArray(System.nanoTime())))))
}
