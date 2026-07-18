package com.decentralchain.state.patch

import cats.implicits.{catsSyntaxSemigroup, toFoldableOps}
import com.decentralchain.account.{Address, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.state.{Blockchain, Height, LeaseBalance, LeaseDetails, Portfolio, StateSnapshot}
import play.api.libs.json.{Json, Reads}

// Networks restricted to 'T'/'W': this is a one-time historical correction for lease state that predates
// this chain and has no patch data file for it (patches/CancelLeasesToDisabledAliases-<char>.json only
// exists for the original Waves testnet/mainnet). PatchOnFeature treats an EMPTY networks set as "applies
// everywhere" (unlike PatchAtHeight, where an empty height map means "never applies"), so leaving this
// empty made the patch try to load nonexistent data and crash on any other chain once SynchronousCalls
// activates.
case object CancelLeasesToDisabledAliases extends PatchOnFeature(BlockchainFeatures.SynchronousCalls, Set('T', 'W')) {
  private case class CancelDetails(
      id: String,
      amount: Long,
      senderPublicKey: String,
      recipientAddress: String,
      recipientAlias: String,
      height: Int
  )

  def patchData(chainId: Char): Map[ByteStr, (Map[Address, Portfolio], Address)] = {
    implicit val cancelDetailsReads: Reads[CancelDetails] = Json.reads

    readPatchData[Seq[CancelDetails]](chainId).map { cancelDetails =>
      val leaseId          = ByteStr(Base58.decode(cancelDetails.id))
      val sender           = PublicKey(Base58.decode(cancelDetails.senderPublicKey))
      val recipientAddress = Address.fromString(cancelDetails.recipientAddress, expectedChainId = Some(chainId.toByte)).explicitGet()
      leaseId -> (
        Portfolio
          .combine(
            Map(sender.toAddress(chainId.toByte) -> Portfolio(lease = LeaseBalance(0, -cancelDetails.amount))),
            Map(recipientAddress -> Portfolio(lease = LeaseBalance(-cancelDetails.amount, 0)))
          )
          .explicitGet(),
        recipientAddress
      )
    }.toMap
  }

  override def apply(blockchain: Blockchain): StateSnapshot = {
    val (leaseBalances, leaseStates) =
      patchData(blockchain.settings.addressSchemeCharacter).toSeq.map { case (id, (pf, _)) =>
        (
          pf,
          StateSnapshot(
            cancelledLeases = Map(id -> LeaseDetails.Status.Expired(Height(blockchain.height)))
          )
        )
      }.unzip
    val combinedLeaseBalances = leaseBalances.reduce(Portfolio.combine(_, _).explicitGet())
    val leaseBalancesSnapshot = StateSnapshot.ofLeaseBalances(combinedLeaseBalances.view.mapValues(_.lease).toMap, blockchain)
    leaseBalancesSnapshot.explicitGet() |+| leaseStates.combineAll
  }
}
