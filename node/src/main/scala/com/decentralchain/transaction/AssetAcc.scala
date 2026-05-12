package com.decentralchain.transaction

import com.decentralchain.account.Address

case class AssetAcc(account: Address, assetId: Option[Asset])
