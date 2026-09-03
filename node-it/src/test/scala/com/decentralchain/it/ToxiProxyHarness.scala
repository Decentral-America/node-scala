package com.decentralchain.it

import com.dimafeng.testcontainers.GenericContainer
import com.github.dockerjava.api.command.InspectContainerResponse
import com.decentralchain.it.ToxiProxyHarness.ConfigurableToxicProxyContainer
import com.decentralchain.it.ToxiProxyHarness.ConfigurableToxicProxyContainer.ContainerProxy
import eu.rekawek.toxiproxy.Proxy as ToxiProxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.{ToxicDirection, ToxicList}
import org.testcontainers.utility.DockerImageName
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Ports matcher's proven `HasToxiProxy` pattern (matcher/dex-it-common/.../api/HasToxiProxy.scala,
  * backed by matcher's own `ConfigurableToxicProxyContainer`) into node-it: a single
  * `shopify/toxiproxy` container proxying a node's P2P port, so a scenario can inject
  * latency/bandwidth-throttling/full-cut on that link without the binary all-or-nothing of
  * `Docker.disconnectFromNetwork` / container kill. node-it had zero degraded-link fault
  * injection before this file.
  *
  * Adaptation note (see task-1-report.md for the full investigation): matcher's template assumes a
  * `BaseContainersKit` self-type exposing `network: org.testcontainers.containers.Network`,
  * `networkName: String` and `getIp(...)`. node-it's own base trait, `DockerBased`
  * (node-it/src/test/scala/com/decentralchain/it/DockerBased.scala), exposes none of these -- it only
  * exposes `docker: Docker` (a lazily-created singleton). node-it's `Docker` class
  * (node-it/src/test/scala/com/decentralchain/it/Docker.scala) manages its own docker-java network
  * (`com.github.dockerjava.api.model.Network`, created directly via the docker-java client, exposed
  * only via `def createNetwork: Network`) rather than a testcontainers-scala `Network`. Because of
  * that type mismatch, this harness cannot use `GenericContainer#withNetwork`/`withNetworkAliases`
  * (which require a testcontainers-owned `Network`); instead it attaches the toxiproxy container to
  * node-it's real network by name via `withCreateContainerCmdModifier`, exactly the same low-level
  * docker-java escape hatch node-it's own `Docker.startNodeInternal` uses
  * (`HostConfig.withNetworkMode(dccNetwork.getName)`, Docker.scala:264). Docker's embedded DNS then
  * resolves both the toxiproxy container and node containers by their `--name` on that shared
  * user-defined network, so no separate "alias" mechanism is required.
  *
  * Also note: matcher's original pins the toxiproxy container to a reserved static IP
  * (`getIp(13)`) within `BaseContainersKit`'s address scheme. node-it's `Docker` has no equivalent
  * public (or even reserved) slot for auxiliary containers -- `ipForNode` is `private` and only
  * covers node containers -- so this port leaves the toxiproxy container's address to Docker's
  * normal IPAM allocation within the network's `/28` subnet instead of forcing a fixed one.
  *
  * `ConfigurableToxicProxyContainer` itself (below) is also a direct port of matcher's version
  * (matcher/dex-it-common/.../docker/ConfigurableToxicProxyContainer.scala). It's inlined into this
  * one file rather than split out because node-it has no shared "-common" module the way matcher's
  * dex-it/dex-it-common split does, and this task is scoped to a single new file.
  */
trait ToxiProxyHarness { self: DockerBased =>

  /** Enough headroom for a small number of proxied node P2P ports in one suite; matcher's template
    * sized this to its own fixed set of well-known extension ports (gRPC extensions), but node-it
    * has no such fixed port list -- callers proxy whatever `dcc.network.port` a given node resolves
    * to (see `Docker.scala:228`), so this is a plain capacity bound instead.
    */
  private val maxProxiedPorts = 4

  protected val toxiProxyHostName: String = s"${self.docker.createNetwork.getName}-toxiproxy"

  protected val toxiContainer: ConfigurableToxicProxyContainer = mkToxiProxyContainer

  private def mkToxiProxyContainer: ConfigurableToxicProxyContainer = {
    val c = new ConfigurableToxicProxyContainer("shopify/toxiproxy:2.1.0", maxProxiedPorts)
    // Deliberately NOT attached to `dccNetwork` here (contrast with node containers, which set
    // `withNetworkMode` at create time) -- see the doc comment on `toxiContainer.start()` below and on
    // `Docker.attachToNetworkAtFixedAddress` for why the network attachment has to happen as a separate,
    // later step instead.
    c.container.withCreateContainerCmdModifier { cmd =>
      cmd.withName(toxiProxyHostName)
      ()
    }
    c
  }

  protected def getInnerToxiProxyPort(proxy: ContainerProxy): Int =
    toxiContainer.getContainerInfo.getNetworkSettings.getPorts.getBindings.asScala
      .find { case (_, bindings) => Option(bindings).flatMap(_.headOption).exists(_.getHostPortSpec == proxy.proxyPort.toString) }
      .map(_._1.getPort)
      .getOrElse(throw new IllegalStateException(s"There is no inner port for proxied one: ${proxy.proxyPort}"))

  /** Proxy `hostname:port` (typically a node container's `--name` and P2P port, the latter read from
    * its resolved `dcc.network.port` config, the same key `Docker.scala:228` reads). Returns a handle
    * for injecting latency/bandwidth/cut toxics.
    */
  protected def mkToxiProxy(hostname: String, port: Int): ContainerProxy = toxiContainer.getProxy(hostname, port)

  // Started WITHOUT `dccNetwork` attached (see `mkToxiProxyContainer` above) so its control-port wait
  // strategy and host access (both go through the host-mapped port on whatever default network
  // testcontainers gives it) come up normally, unaffected by anything to do with node-it's own network.
  toxiContainer.start()

  // *Then* attach it to `dccNetwork` at a fixed, never-node-assigned address (`Docker.auxiliaryContainerAddress`).
  // Two real, empirically-confirmed problems this avoids, in order:
  //   1. Attaching at create time (as node containers do via `withNetworkMode`) and leaving the address to
  //      Docker's default IPAM let it land on the SAME address a real node container reserves moments
  //      later, which fails that node's container creation outright with "failed to set up container
  //      networking: Address already in use" -- hit running this harness against a real multi-node cluster
  //      for the first time (node-it's own test suites had never done that before this file existed).
  //   2. Fixing #1 by pinning the address the way `startNodeInternal` pins node IPs (disconnect the
  //      container's current network, then reconnect with an explicit IP) breaks the container as soon as
  //      it's already RUNNING with a published host port: disconnecting an already-started container's
  //      sole network attachment tears down the NAT/port-publish rule for that port along with it (confirmed
  //      directly against a throwaway container: the host-mapped port started returning "Connection reset"
  //      immediately after a disconnect+reconnect cycle, even though the reconnect itself succeeded and the
  //      container kept running). ADDING a second network attachment to a container that keeps its original
  //      one intact does not have this problem (confirmed the same way) -- hence attach-after-start via
  //      `attachToNetworkAtFixedAddress`, not the node-container disconnect/reconnect pattern.
  self.docker.attachToNetworkAtFixedAddress(toxiContainer.getContainerInfo.getId, self.docker.auxiliaryContainerAddress)
}

object ToxiProxyHarness {

  /** A direct port of matcher's `ConfigurableToxicProxyContainer`
    * (matcher/dex-it-common/src/main/scala/com/decentralchain/dex/it/docker/ConfigurableToxicProxyContainer.scala).
    * Matcher's version lives in a shared module (`dex-it-common`) that node-it does not depend on and
    * has no equivalent of, so rather than add a cross-repo dependency this reproduces the piece
    * node-it actually needs, unchanged in behavior: a `shopify/toxiproxy` container wrapped for
    * testcontainers-scala, exposing named upstream proxies and toxic control.
    */
  class ConfigurableToxicProxyContainer(image: String, maxExposedPorts: Int = 0) extends GenericContainer(GenericContainer(dockerImage = image)) {

    private val TOXIPROXY_CONTROL_PORT: Int = 8474
    private val FIRST_PROXIED_PORT: Int     = 8666
    private val LAST_PROXIED_PORT: Int      = 8666 + maxExposedPorts

    private var client: Option[ToxiproxyClient] = None
    private val proxies                         = mutable.Map.empty[String, ContainerProxy]
    private val nextPort                        = new AtomicInteger(FIRST_PROXIED_PORT)

    DockerImageName.parse(image).assertCompatibleWith(DockerImageName.parse("shopify/toxiproxy"))
    container.addExposedPort(TOXIPROXY_CONTROL_PORT)
    container.setWaitStrategy(new HttpWaitStrategy().forPath("/version").forPort(TOXIPROXY_CONTROL_PORT))
    for (i <- FIRST_PROXIED_PORT to LAST_PROXIED_PORT) container.addExposedPort(i)

    override def start(): Unit = {
      super.start()
      client = Some(new ToxiproxyClient(container.getHost, container.getMappedPort(TOXIPROXY_CONTROL_PORT)))
    }

    def getControlPort: Int                        = container.getMappedPort(TOXIPROXY_CONTROL_PORT)
    def getContainerInfo: InspectContainerResponse = containerInfo

    def getProxy(hostname: String, port: Int): ContainerProxy = {
      val upstream = s"$hostname:$port"
      proxies.getOrElseUpdate(upstream, mkNewProxy(upstream))
    }

    private def mkNewProxy(upstream: String): ContainerProxy = {
      val toxiPort = nextPort.getAndIncrement()
      if (toxiPort > LAST_PROXIED_PORT) throw new IllegalStateException("Maximum number of proxies exceeded")
      val proxy = client
        .getOrElse(throw new IllegalStateException("Cannot get proxy from toxiProxy because client isn't presented"))
        .createProxy(upstream, s"0.0.0.0:$toxiPort", upstream)
      val mappedPort        = container.getMappedPort(toxiPort)
      val newContainerProxy = ContainerProxy(proxy, container.getHost, mappedPort, toxiPort)
      proxies.put(upstream, newContainerProxy)
      newContainerProxy
    }
  }

  object ConfigurableToxicProxyContainer {

    case class ContainerProxy(toxi: ToxiProxy, containerIpAddress: String, proxyPort: Int, originalProxyPort: Int) {
      private val CUT_CONNECTION_DOWNSTREAM = "CUT_CONNECTION_DOWNSTREAM"
      private val CUT_CONNECTION_UPSTREAM   = "CUT_CONNECTION_UPSTREAM"

      private var isCurrentlyCut: Boolean = false

      def getName: String   = toxi.getName
      def toxics: ToxicList = toxi.toxics

      def setConnectionCut(shouldCutConnection: Boolean): Unit =
        if (shouldCutConnection) {
          toxics.bandwidth(CUT_CONNECTION_DOWNSTREAM, ToxicDirection.DOWNSTREAM, 0)
          toxics.bandwidth(CUT_CONNECTION_UPSTREAM, ToxicDirection.UPSTREAM, 0)
          isCurrentlyCut = true
        } else if (isCurrentlyCut) {
          toxics.get(CUT_CONNECTION_DOWNSTREAM).remove()
          toxics.get(CUT_CONNECTION_UPSTREAM).remove()
          isCurrentlyCut = false
        }
    }
  }
}
