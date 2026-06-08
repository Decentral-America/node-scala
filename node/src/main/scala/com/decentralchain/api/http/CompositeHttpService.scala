package com.decentralchain.api.http

import com.decentralchain.settings.RestAPISettings
import com.decentralchain.utils.ScorexLogging
import kamon.Kamon
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.HttpMethods.*
import org.apache.pekko.http.scaladsl.model.headers.*
import org.apache.pekko.http.scaladsl.server.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.RouteResult.Complete
import org.slf4j.event.Level

import scala.io.Source

case class CompositeHttpService(routes: Seq[ApiRoute], settings: RestAPISettings) extends ScorexLogging {

  private val redirectToApiDocs = redirect("/api-docs/index.html", StatusCodes.PermanentRedirect)
  private val swaggerRoute: Route =
    (pathEndOrSingleSlash | path("swagger") | path("api-docs"))(redirectToApiDocs) ~
      pathPrefix("api-docs") {
        pathEndOrSingleSlash(redirectToApiDocs) ~
          path("openapi.yaml")(complete(patchedSwaggerJson)) ~
          getFromResourceDirectory("swagger-ui")
      }

  private val requestTimestamp = AttributeKey[Long]("timestamp")

  private def addTimestamp(req: HttpRequest): HttpRequest = req.addAttribute(requestTimestamp, System.nanoTime())

  val compositeRoute: Route = mapRequest(addTimestamp) {
    Kamon
      .currentSpan()
      .mark("processing.start")

    extractRequest { req =>
      mapRouteResultPF(logRequestResponse(req)) {
        extendRoute(routes.map(_.route).reduce(_ ~ _)) ~ swaggerRoute ~ complete(StatusCodes.NotFound)
      }
    }
  }

  val loggingCompositeRoute: Route = Route.seal(compositeRoute)

  private val CorsAllowAllOrigin = "origin-from-request"

  private def logRequestResponse(req: HttpRequest): PartialFunction[RouteResult, RouteResult] = { case r @ Complete(resp) =>
    log.underlying
      .atLevel(if (resp.status == StatusCodes.OK) Level.INFO else Level.WARN)
      .log { () =>
        s"HTTP ${resp.status.value} from ${req.method.value} ${req.uri}${req.attribute(requestTimestamp).fold("")(ts => f" in ${(System.nanoTime() - ts) * 1e-6}%.3f ms")}"
      }
    r
  }

  private def preflightCorsHeaders(requestOrigin: Option[Origin]): Seq[HttpHeader] =
    requestOrigin
      .flatMap(_.origins.headOption)
      .fold(Seq[HttpHeader]()) { _ =>
        Seq(
          `Access-Control-Allow-Headers`(settings.corsHeaders.accessControlAllowHeaders),
          `Access-Control-Allow-Methods`(settings.corsHeaders.accessControlAllowMethods.flatMap(getForKeyCaseInsensitive))
        )
      }

  private val securityHeaders: Seq[HttpHeader] = Seq(
    RawHeader("X-Content-Type-Options", "nosniff"),
    RawHeader("X-Frame-Options", "DENY"),
    // X-XSS-Protection: 0 — explicitly disables the deprecated browser XSS auditor.
    // Setting "1; mode=block" is harmful: the auditor was removed from Chrome (v78+),
    // Firefox (never), and Safari (iOS 15.4+) and the blocking behaviour itself was
    // exploitable for XSS reflection (OWASP ref: Testing_for_Reflected_XSS §12.2).
    RawHeader("X-XSS-Protection", "0"),
    RawHeader("Cache-Control", "no-store"),
    RawHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
  )

  private def corsHeaders(requestOrigin: Option[Origin]): Seq[HttpHeader] =
    requestOrigin
      .flatMap(_.origins.headOption)
      .fold(Seq[HttpHeader]()) { requestOriginValue =>
        val responseOrigin =
          settings.corsHeaders.accessControlAllowOrigin match {
            case "*"                => `Access-Control-Allow-Origin`.*
            case CorsAllowAllOrigin => `Access-Control-Allow-Origin`(requestOriginValue)
            case origin             => `Access-Control-Allow-Origin`(origin)
          }
        Seq(responseOrigin, `Access-Control-Allow-Credentials`(settings.corsHeaders.accessControlAllowCredentials))
      }

  private def extendRoute(base: Route): Route = handleAllExceptions {
    optionalHeaderValueByType(Origin) { maybeOrigin =>
      respondWithDefaultHeaders(securityHeaders ++ corsHeaders(maybeOrigin)) {
        options {
          respondWithDefaultHeaders(preflightCorsHeaders(maybeOrigin)) {
            complete(StatusCodes.OK)
          }
        } ~ base
      }
    }
  }

  private lazy val patchedSwaggerJson = {
    import com.decentralchain.Version
    import com.decentralchain.account.AddressScheme

    def chainIdString: String =
      if (Character.isAlphabetic(AddressScheme.current.chainId)) AddressScheme.current.chainId.toChar.toString
      else s"#${AddressScheme.current.chainId}"

    HttpEntity(
      MediaType.customWithFixedCharset("text", "x-yaml", HttpCharsets.`UTF-8`, List("yaml")),
      Source
        .fromResource("swagger-ui/openapi.yaml")
        .mkString
        .replace("{{version}}", Version.VersionString)
        .replace("{{chainId}}", chainIdString)
    )
  }
}
