package com.decentralchain.api.http

import org.apache.pekko.http.scaladsl.server.*
import com.decentralchain.api.http.ApiError.ApiKeyNotValid
import com.decentralchain.common.utils.Base58
import com.decentralchain.crypto
import com.decentralchain.settings.RestAPISettings
import com.decentralchain.utils.*

import java.security.MessageDigest

trait ApiRoute extends Directives with CustomDirectives with ApiMarshallers with ScorexLogging {
  def route: Route
}

trait AuthRoute { this: ApiRoute =>
  def settings: RestAPISettings

  protected lazy val apiKeyHash: Option[Array[Byte]] = Base58.tryDecode(settings.apiKeyHash).toOption

  def withAuth: Directive0 = apiKeyHash.fold[Directive0](complete(ApiKeyNotValid)) { hashFromSettings =>
    val xApiKey   = optionalHeaderValueByType(`X-Api-Key`)
    val legacyKey = optionalHeaderValueByType(api_key)
    (xApiKey & legacyKey).tflatMap { case (xKey, legKey) =>
      val providedKey: Option[String] = xKey.map(_.value).orElse(legKey.map(_.value))
      providedKey match {
        case Some(k) if MessageDigest.isEqual(crypto.secureHash(k.utf8Bytes), hashFromSettings) => pass
        case _                                                                                  => complete(ApiKeyNotValid)
      }
    }
  }
}
