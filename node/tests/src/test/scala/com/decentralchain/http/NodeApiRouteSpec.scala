package com.decentralchain.http

import com.decentralchain.Shutdownable
import com.decentralchain.api.http.NodeApiRoute
import com.decentralchain.db.WithDomain
import com.decentralchain.test.DomainPresets.*
import play.api.libs.json.JsValue

class NodeApiRouteSpec extends RouteSpec("/node") with RestAPISettingsHelper with WithDomain {

  private val noopApplication: Shutdownable = () => ()

  routePath("/status exposes the deployment's generationPeriodLength") in {
    withDomain(RideV6) { d =>
      d.appendBlock()

      val route = NodeApiRoute(restAPISettings, d.blockchain, noopApplication).route

      Get(routePath("/status")) ~> route ~> check {
        val response = responseAs[JsValue]
        (response \ "generationPeriodLength").as[Int] shouldBe d.blockchain.settings.functionalitySettings.generationPeriodLength
        (response \ "blockchainHeight").as[Int] shouldBe d.blockchain.height
      }
    }
  }

  routePath("/status reflects a different network's generationPeriodLength") in {
    val settings = RideV6.copy(blockchainSettings =
      RideV6.blockchainSettings.copy(functionalitySettings = RideV6.blockchainSettings.functionalitySettings.copy(generationPeriodLength = 3000))
    )

    withDomain(settings) { d =>
      d.appendBlock()

      val route = NodeApiRoute(restAPISettings, d.blockchain, noopApplication).route

      Get(routePath("/status")) ~> route ~> check {
        (responseAs[JsValue] \ "generationPeriodLength").as[Int] shouldBe 3000
      }
    }
  }
}
