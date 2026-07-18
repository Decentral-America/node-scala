package com.decentralchain.state.patch

import com.decentralchain.account.AddressScheme
import com.decentralchain.features.BlockchainFeature
import com.decentralchain.state.{Blockchain, StateSnapshot}
import play.api.libs.json.{Json, Reads}

import scala.io.Source

trait PatchDataLoader {
  protected def readPatchData[T: Reads](chainId: Char): T =
    Json
      .parse(
        Source
          .fromResource(s"patches/${getClass.getSimpleName.replace("$", "")}-$chainId.json")
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
  override def isDefinedAt(blockchain: Blockchain): Boolean = {
    (networks.isEmpty || networks.contains(blockchain.settings.addressSchemeCharacter)) &&
    blockchain.featureActivationHeight(feature).contains(blockchain.height)
  }
}
