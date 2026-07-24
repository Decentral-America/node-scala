package com.decentralchain.it.sync.finalization

import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.{Docker, ToxiProxyHarness}
import com.decentralchain.test.NumericExt
import com.typesafe.config.ConfigFactory
import eu.rekawek.toxiproxy.model.ToxicDirection
import monix.eval.Coeval

import scala.concurrent.duration.DurationInt

/** Same 4-node HotStuff cluster as `FourNodeHotStuffTestSuite` (via the shared `HotStuffFourNodeSuite`
  * base -- see that file's doc comment for why this does NOT extend `FourNodeHotStuffTestSuite` itself),
  * but instead of a binary partition (`Docker.disconnectFromNetwork`), the link between exactly two of
  * the four nodes is degraded (latency + bandwidth throttle) via a real ToxiProxy container. Checks the
  * same safety invariant already used by the happy-path/partition scenarios: finalizedHeight must never
  * regress on any node.
  *
  * == Why the default 4-node bring-up can't just be proxied "after the fact" ==
  *
  * Creating a ToxiProxy container that proxies a node's P2P port does NOT, by itself, reroute any
  * traffic through it -- something has to be configured, at connection-establishment time, to dial the
  * proxy's address instead of the target node's real address for that one pairwise link. Node-it's
  * normal cluster bring-up (`Docker.startNodeInternal`, driven by `NodesFromDocker`'s default
  * `dockerNodes = docker.startNodes(nodeConfigs)`) always computes each node's `known-peers` internally,
  * from the REAL `networkAddress` of whichever peers are already running (`Docker.scala`'s private
  * `peersFor`/`peersOverrides`) -- there is no lever in `nodeConfigs: Seq[Config]` to override that for
  * just one pairwise link, because `peersOverrides` is layered as the highest-priority config and always
  * wins over anything the same key is given inside a node's own `Config` (see `Docker.startNodeInternal`,
  * `peersOverrides.withFallback(nodeConfig)`). So this suite overrides `dockerNodes` itself (the one hook
  * `NodesFromDocker` exposes for this) and brings the cluster up in a specific order:
  *
  *   1. Start the "far side" of the degraded link (`farSide`) first, with normal `autoConnect`. Nothing
  *      is registered yet, so its own known-peers computation is a no-op regardless.
  *   2. Now that `farSide`'s real container name and resolved P2P port are known, create its ToxiProxy
  *      (`mkToxiProxy(farSide.containerName, farSide.networkAddress.getPort)` -- see the container-name
  *      note below).
  *   3. Start the "near side" (`leader`, the node this scenario sends transfers from) with
  *      `autoConnect = false` and an explicit `dcc.network.known-peers` override pointing ONLY at the
  *      proxy's address. With `autoConnect = false`, `Docker.startNodeInternal` skips its own
  *      known-peers computation entirely (`peersOverrides = ConfigFactory.empty()`), so this override is
  *      what actually takes effect -- this is the one line that reroutes the link.
  *   4. Start the remaining two nodes normally (`autoConnect = true`): they learn the REAL addresses of
  *      both `leader` and `farSide` and connect directly to both, so those four links stay healthy. Only
  *      `leader<->farSide` is degraded.
  *
  * == Why this survives peer-gossip (and isn't just a connection that happens once) ==
  *
  * Node-it's gossip (`PeerSynchronizer`/`dcc.network.peers-broadcast-interval`) means the two untouched
  * nodes WILL eventually tell `leader` about `farSide`'s real (unproxied) address, and `leader`'s own
  * periodic reconnect task (`NetworkServer.scheduleConnectTask`) will then try to dial it directly --
  * this is exactly the "second critical issue" this suite has to actually resolve, not hand-wave past.
  * It resolves harmlessly because of how peer-connection dedup works
  * (`HandshakeHandler.channelRead`, keyed by the remote's node-nonce, not by IP): whichever physical TCP
  * connection between the two nodes completes its handshake FIRST is kept; any later duplicate handshake
  * for the same peer nonce is closed immediately (`"Already connected to peer with nonce ... -- closing
  * this duplicate"`), before any block/vote/tx traffic crosses it. `leader`'s proxied connection is
  * attempted immediately at startup (from its own `known-peers`, no gossip round-trip needed), while a
  * competing direct connection needs at least one extra full hop (some other node connects to `farSide`,
  * gossips its address to `leader`, `leader`'s reconnect task then fires) -- so the proxied connection
  * reliably wins the race and pins the logical link to the proxied path for the rest of the test. This is
  * the same dedup mechanism `NetworkUniqueConnectionsTestSuite` already exercises directly, not a new or
  * speculative behavior.
  *
  * == Container-name fix (the "first critical issue") ==
  *
  * `mkToxiProxy(hostname, port)` needs `hostname` to be the peer's REAL Docker container `--name`, so
  * ToxiProxy's own container (itself attached to the same Docker network) can reach it. `DockerNode` used
  * to expose no such thing -- only the logical `dcc.network.node-name` (via `Node.name`) -- because the
  * real container name (`s"${dccNetwork.getName}-$nodeName$jenkinsJobIdFromEnv"`) was constructed only
  * locally inside `Docker.scala`'s private `startNodeInternal` and never stored back onto the node. Fixed
  * with a small, targeted addition: `DockerNode` now has a `containerName: String` field, populated from
  * the same local value `startNodeInternal` already computes for the container's `withName(...)` call
  * (`Docker.scala`), rather than re-deriving it via a separate `inspectContainerCmd` lookup.
  */
class DegradedLinkHotStuffTestSuite extends HotStuffFourNodeSuite with ToxiProxyHarness {

  override protected val dockerNodes: Coeval[Seq[Docker.DockerNode]] = Coeval.evalOnce {
    val configs = nodeConfigs

    // 1. Far side first: nothing else is registered yet, so autoConnect=true is a harmless no-op here.
    val farSide = docker.startNode(configs(1), autoConnect = true)

    // 2. Proxy for the far side's real address, now that its container name + resolved P2P port exist.
    //    `networkAddress.getPort` (not `nodeExternalPort`, which is the HOST-mapped port for out-of-cluster
    //    access) is the container-internal P2P port other containers on the same Docker network dial.
    val proxiedLink  = mkToxiProxy(farSide.containerName, farSide.networkAddress.getPort)
    val proxyAddress = s"$toxiProxyHostName:${proxiedLink.originalProxyPort}"

    // 3. Near side, wired to dial ONLY the proxy -- see class doc for why autoConnect must be false here.
    val leaderOverride = ConfigFactory.parseString(s"""dcc.network.known-peers = ["$proxyAddress"]""")
    val leader          = docker.startNode(leaderOverride.withFallback(configs.head), autoConnect = false)

    // 4. The other two nodes: normal bring-up, real addresses, healthy links to both leader and farSide.
    val other1 = docker.startNode(configs(2), autoConnect = true)
    val other2 = docker.startNode(configs(3), autoConnect = true)

    // Preserve nodeConfigs' original ordering (leader, farSide, other, other) so `hsNodes(1)` below still
    // means "the far side of the degraded link", matching this suite's own setup above.
    Seq(leader, farSide, other1, other2)
  }

  "T2 HotStuff on a real 4-node cluster with a degraded (not fully cut) link between two nodes" - {
    "keeps finalizing without any node's finalizedHeight regressing" in {
      val proxiedLink = mkToxiProxy(hsNodes(1).containerName, hsNodes(1).networkAddress.getPort)
      proxiedLink.toxics.latency("degraded-link-latency", ToxicDirection.DOWNSTREAM, 800)
      proxiedLink.toxics.bandwidth("degraded-link-bandwidth", ToxicDirection.DOWNSTREAM, 32) // 32 KB/s

      val start    = leader.finalizedHeight
      val target   = start + 2
      val deadline = 6.minutes.fromNow // longer than the happy-path deadline: the link is degraded, not down
      var done     = false
      while (!done && deadline.hasTimeLeft()) {
        leader.transfer(leader.keyPair, hsNodes(1).address, 1.dcc, waitForTx = true)
        val fhs = hsNodes.map(_.finalizedHeight)
        fhs.foreach(fh => if (fh < start) fail(s"finalized height regressed below $start under a degraded link: got $fh"))
        done = fhs.forall(_ >= target)
      }
      if (!done)
        fail(s"HotStuff-enabled cluster with a degraded link did not finalize to $target within the deadline; per-node finalized=${hsNodes.map(_.finalizedHeight)}")

      proxiedLink.toxics.get("degraded-link-latency").remove()
      proxiedLink.toxics.get("degraded-link-bandwidth").remove()
    }
  }
}
