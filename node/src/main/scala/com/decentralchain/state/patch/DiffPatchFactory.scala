package com.decentralchain.state.patch

import com.decentralchain.account.AddressScheme
import com.decentralchain.features.BlockchainFeature
import com.decentralchain.state.{Blockchain, StateSnapshot}
import play.api.libs.json.{Json, Reads}

import scala.io.Source

trait PatchDataLoader {
  protected def readPatchData[T: Reads](): T =
    Json
      .parse(
        Source
          .fromResource(s"patches/${getClass.getSimpleName.replace("$", "")}-${AddressScheme.current.chainId.toChar}.json")
          .mkString
      )
      .as[T]
}

trait DiffPatchFactory extends PartialFunction[Blockchain, StateSnapshot]

abstract class PatchAtHeight(chainIdToHeight: (Char, Int)*) extends PatchDataLoader with DiffPatchFactory {
  private val chainIdToHeightMap         = chainIdToHeight.toMap
  protected def patchHeight: Option[Int] = chainIdToHeightMap.get(AddressScheme.current.chainId.toChar)

  override def isDefinedAt(blockchain: Blockchain): Boolean =
    chainIdToHeightMap.get(blockchain.settings.addressSchemeCharacter).contains(blockchain.height)
}

abstract class PatchOnFeature(feature: BlockchainFeature, networks: Set[Char] = Set.empty) extends PatchDataLoader with DiffPatchFactory {
  // NOTE: an empty `networks` set means "applies to no network" (the safe, intuitive reading),
  // not "applies to every network". CancelLeasesToDisabledAliases, the only consumer of this
  // class, was accidentally shipped with Set.empty intending to disable it for DCC entirely (it's
  // a Waves-mainnet-specific historical lease cleanup DCC's clean chain never needed) -- the old
  // `networks.isEmpty || ...` logic instead made it fire on every network, including stagenet/
  // testnet chain IDs with no corresponding patch-data resource file, throwing the first time any
  // node crosses the SynchronousCalls activation height there. See
  // docs/consensus-divergences-from-upstream.md and CONSENSUS-BUG-INVESTIGATION-REFERENCE.md §5.
  override def isDefinedAt(blockchain: Blockchain): Boolean = {
    networks.contains(blockchain.settings.addressSchemeCharacter) &&
    blockchain.featureActivationHeight(feature).contains(blockchain.height)
  }
}
