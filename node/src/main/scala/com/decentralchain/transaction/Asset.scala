package com.decentralchain.transaction

import com.google.common.collect.Interners
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.transaction.assets.exchange.AssetPair
import play.api.libs.json.*
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import scala.util.Success

sealed trait Asset
object Asset {
  final case class IssuedAsset(id: ByteStr) extends Asset {
    override def toString: String = id.toString
    override def hashCode(): Int  = id.hashCode()
  }

  object IssuedAsset {
    private val interner                                                                   = Interners.newWeakInterner[IssuedAsset]()
    def apply(id: ByteStr): IssuedAsset                                                    = interner.intern(new IssuedAsset(id))
    def fromString[T](str: String, onSuccess: IssuedAsset => T, onFailure: String => T): T =
      Base58.tryDecodeWithLimit(str) match {
        case Success(arr) if arr.length != AssetIdLength => onFailure(s"Invalid validation. Size of asset id $str not equal $AssetIdLength bytes")
        case Success(arr)                                => onSuccess(IssuedAsset(ByteStr(arr)))
        case _                                           => onFailure("Expected base58-encoded assetId")
      }
  }

  case object Dcc extends Asset

  val DccName = "DCC"

  implicit val assetReads: Reads[IssuedAsset] = Reads {
    case JsString(str) => IssuedAsset.fromString(str, JsSuccess(_), JsError(_))
    case _             => JsError("Expected base58-encoded assetId")
  }
  implicit val assetWrites: Writes[IssuedAsset] = Writes { asset =>
    JsString(asset.id.toString)
  }

  implicit val assetIdReads: Reads[Asset]   = assetReads(false)
  implicit val assetIdWrites: Writes[Asset] = Writes {
    case Dcc             => JsNull
    case IssuedAsset(id) => JsString(id.toString)
  }

  object Formats {
    implicit val assetJsonFormat: Format[IssuedAsset] = Format(assetReads, assetWrites)
    implicit val assetIdJsonFormat: Format[Asset]     = Format(assetIdReads, assetIdWrites)
  }

  implicit val assetConfigReader: ConfigReader[Asset] =
    ConfigReader[String].emap(s => AssetPair.extractAssetId(s).fold(ex => Left(CannotConvert(s, "Asset", ex.getMessage)), Right(_)))

  def fromString(maybeStr: Option[String]): Asset = {
    maybeStr.map(x => IssuedAsset(ByteStr.decodeBase58(x).get)).getOrElse(Dcc)
  }

  def fromCompatId(maybeBStr: Option[ByteStr]): Asset = {
    maybeBStr.fold[Asset](Dcc)(IssuedAsset(_))
  }

  implicit class AssetIdOps(private val ai: Asset) extends AnyVal {
    def byteRepr: Array[Byte] = ai match {
      case Dcc             => Array(0: Byte)
      case IssuedAsset(id) => (1: Byte) +: id.arr
    }

    def compatId: Option[ByteStr] = ai match {
      case Dcc             => None
      case IssuedAsset(id) => Some(id)
    }

    def maybeBase58Repr: Option[String] = ai match {
      case Dcc             => None
      case IssuedAsset(id) => Some(id.toString)
    }

    def fold[A](onDcc: => A)(onAsset: IssuedAsset => A): A = ai match {
      case Dcc                    => onDcc
      case asset @ IssuedAsset(_) => onAsset(asset)
    }
  }

  def assetReads(allowDccStr: Boolean): Reads[Asset] = Reads {
    case json: JsString =>
      if (json.value.isEmpty || (allowDccStr && json.value == DccName)) JsSuccess(Dcc) else assetReads.reads(json)
    case JsNull => JsSuccess(Dcc)
    case _      => JsError("Expected base58-encoded assetId or null")
  }
}
