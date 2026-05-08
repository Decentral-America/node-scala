package com.wavesplatform.api.http

import org.apache.pekko.http.scaladsl.server.*
import com.wavesplatform.api.http.ApiError.ApiKeyNotValid
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.crypto
import com.wavesplatform.settings.RestAPISettings
import com.wavesplatform.utils.*

import java.security.MessageDigest

trait ApiRoute extends Directives with CustomDirectives with ApiMarshallers with ScorexLogging {
  def route: Route
}

trait AuthRoute { this: ApiRoute =>
  def settings: RestAPISettings

  protected lazy val apiKeyHash: Option[Array[Byte]] = Base58.tryDecode(settings.apiKeyHash).toOption

  def withAuth: Directive0 = apiKeyHash.fold[Directive0](complete(ApiKeyNotValid)) { hashFromSettings =>
    val xApiKey = optionalHeaderValueByType(`X-Api-Key`)
    val legacyKey = optionalHeaderValueByType(api_key)
    (xApiKey & legacyKey).tflatMap { case (xKey, legKey) =>
      val providedKey = xKey.orElse(legKey)
      providedKey match {
        case Some(k) if MessageDigest.isEqual(crypto.secureHash(k.value.utf8Bytes), hashFromSettings) => pass
        case _ => complete(ApiKeyNotValid)
      }
    }
  }
}
