package com.decentralchain.it.api

import com.decentralchain.it.Node
import com.decentralchain.network.client.NetworkSender

import scala.concurrent.Future

object AsyncNetworkApi {
  implicit class NodeAsyncNetworkApi(node: Node) {
    import scala.concurrent.ExecutionContext.Implicits.global

    def nonce: Long = System.currentTimeMillis()

    def sendByNetwork(messages: Any*): Future[Unit] = {
      val sender = new NetworkSender(
        node.settings.networkSettings.trafficLogger,
        node.settings.blockchainSettings.addressSchemeCharacter,
        s"it-client-to-${node.name}",
        nonce
      )
      sender.connect(node.networkAddressAccessibleFromHost).flatMap { ch =>
        if (ch.isActive) sender.send(ch, messages*).map(_ => sender.close())
        else {
          sender.close()
          Future.failed(new Exception("Channel is inactive"))
        }
      }
    }
  }
}
