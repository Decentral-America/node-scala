package com.decentralchain.it

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.javaprop.JavaPropsMapper
import com.github.dockerjava.api.command.WaitContainerResultCallback
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientImpl}
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.google.common.primitives.Ints.*
import com.typesafe.config.ConfigFactory.*
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import com.decentralchain.account.AddressScheme
import com.decentralchain.block.Block
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.it.api.AsyncHttpApi.*
import com.decentralchain.it.util.GlobalTimer.instance as timer
import com.decentralchain.settings.*
import com.decentralchain.utils.ScorexLogging
import monix.eval.Coeval
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.io.IOUtils
import org.asynchttpclient.Dsl.*
import pureconfig.ConfigSource

import java.io.{FileOutputStream, IOException}
import java.net.{InetAddress, InetSocketAddress, URI, URL}
import java.nio.file.{Files, Path, Paths}
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, Duration as JDuration}
import java.util.Collections.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.{Properties, List as JList, Map as JMap}
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, blocking}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.{Random, Try}

class Docker(
    suiteConfig: Config = empty,
    tag: String = "",
    enableProfiling: Boolean = false,
    enableDebugger: Boolean = false,
    imageName: String = Docker.NodeImageName
) extends AutoCloseable
    with ScorexLogging {

  import Docker.*

  private val http = asyncHttpClient(
    config()
      .setNettyTimer(timer)
      .setMaxConnections(18)
      .setMaxConnectionsPerHost(3)
      .setMaxRequestRetry(1)
      .setReadTimeout(JDuration.ofSeconds(10))
      .setKeepAlive(false)
      .setRequestTimeout(JDuration.ofSeconds(10))
  )

  private val dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build()
  private val dockerHttpClient = new ApacheDockerHttpClient.Builder()
    .dockerHost(dockerConfig.getDockerHost)
    .build()
  private val client = DockerClientImpl.getInstance(dockerConfig, dockerHttpClient)

  private val nodes     = ConcurrentHashMap.newKeySet[DockerNode]()
  private val isStopped = new AtomicBoolean(false)

  dumpContainers(client.listContainersCmd().exec())
  sys.addShutdownHook {
    log.debug("Shutdown hook")
    close()
  }

  // a random network in 10.x.x.x range
  val networkSeed = Random.nextInt(0x100000) << 4 | 0x0a000000
  // 10.x.x.x/28 network will accommodate up to 13 nodes
  private val networkPrefix = s"${InetAddress.getByAddress(toByteArray(networkSeed)).getHostAddress}/28"

  private val logDir: Coeval[Path] = Coeval.evalOnce {
    val r = Option(System.getProperty("dcc.it.logging.dir"))
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("user.dir"), "logs", RunId, tag.replaceAll("""(\w)\w*\.""", "$1.")))

    Files.createDirectories(r)
    r
  }

  private val genesisOverride = Docker.genesisOverride(Some(suiteConfig))

  private def ipForNode(nodeId: Int) = InetAddress.getByAddress(toByteArray(nodeId & 0xf | networkSeed)).getHostAddress

  private lazy val dccNetwork: Network = {
    val id          = Random.nextInt(Int.MaxValue)
    val networkName = s"dcc-$id"

    def network: Option[Network] =
      try {
        val networks = client.listNetworksCmd().withNameFilter(networkName).exec()
        if (networks.isEmpty) None else Some(networks.get(0))
      } catch {
        case NonFatal(_) => network
      }

    def attempt(rest: Int): Network =
      try {
        network match {
          case Some(n) =>
            val ipam = n
              .getIpam
              .getConfig
              .asScala
              .map(n => s"subnet=${n.getSubnet}, ip range=${n.getIpRange}")
              .mkString(", ")
            log.info(s"Network ${n.getName} (id: ${n.getId}) is created for $tag, ipam: $ipam")
            n
          case None =>
            log.debug(s"Creating network $networkName for $tag")
            // Specify the network manually because of race conditions: https://github.com/moby/moby/issues/20648
            val ipamConfig = new Network.Ipam.Config()
              .withSubnet(networkPrefix)
              .withIpRange(networkPrefix)
              .withGateway(ipForNode(0xe))
            val ipam = new Network.Ipam()
              .withDriver("default")
              .withConfig(ipamConfig)
            val r = client.createNetworkCmd()
              .withName(networkName)
              .withIpam(ipam)
              .withCheckDuplicate(true)
              .exec()
            Option(r.getWarnings).foreach(_.foreach(log.warn(_)))
            attempt(rest - 1)
        }
      } catch {
        case NonFatal(e) =>
          log.warn(s"Can not create a network for $tag", e)
          if (rest == 0) throw e else attempt(rest - 1)
      }

    attempt(5)
  }

  def createNetwork: Network = dccNetwork

  def startNodes(nodeConfigs: Seq[Config]): Seq[DockerNode] = {
    log.trace(s"Starting ${nodeConfigs.size} containers")
    val all = nodeConfigs.map(startNodeInternal(_))
    Await.result(
      Future.traverse(all)(_.waitForStartup()),
      5.minutes
    )
    all
  }

  def startNode(nodeConfig: Config, autoConnect: Boolean = true): DockerNode = {
    val node = startNodeInternal(nodeConfig, autoConnect)
    Await.result(node.waitForStartup(), 3.minutes)
    node
  }

  private def peersFor(nodeName: String): Seq[InetSocketAddress] = {
    nodes.asScala
      .filterNot(_.name == nodeName)
      .filterNot { node =>
        // Exclude disconnected
        client.inspectContainerCmd(node.containerId).exec().getNetworkSettings.getNetworks.isEmpty
      }
      .map(_.networkAddress)
      .toSeq
  }

  private def connectToAll(node: DockerNode): Future[Unit] = {
    def connectToOne(address: InetSocketAddress): Future[Unit] = {
      for {
        _              <- node.connect(address)
        _              <- Future(blocking(Thread.sleep(1.seconds.toMillis)))
        connectedPeers <- node.connectedPeers
        _ <- {
          val connectedAddresses = connectedPeers.map(_.address.replaceAll("""^.*/([\d\.]+).+$""", "$1")).sorted
          log.debug(s"Looking for ${address.getHostName} in $connectedAddresses")
          if (connectedAddresses.contains(address.getHostName)) Future.successful(())
          else {
            log.debug(s"Not found ${address.getHostName}, retrying")
            connectToOne(address)
          }
        }
      } yield ()
    }

    val seedAddresses = peersFor(node.name)
    if (seedAddresses.isEmpty)
      Future.successful(())
    else
      Future
        .traverse(seedAddresses)(connectToOne)
        .map(_ => ())
  }

  private def startNodeInternal(nodeConfig: Config, autoConnect: Boolean = true): DockerNode =
    try {
      val nodeName = nodeConfig.getString("dcc.network.node-name")
      val peersOverrides = if (autoConnect) {
        val otherAddrs = peersFor(nodeName)

        ConfigFactory
          .parseMap(Map("known-peers" -> otherAddrs.map(addr => s"${addr.getHostString}:${addr.getPort}").asJava).asJava)
          .atPath("dcc.network")
      } else ConfigFactory.empty()

      val overrides = peersOverrides
        .withFallback(nodeConfig)
        .withFallback(suiteConfig)
        .withFallback(genesisOverride)
        .withFallback(configTemplate)

      val actualConfig = overrides
        .withFallback(defaultApplication())
        .withFallback(defaultReference())
        .resolve()

      val networkPort          = actualConfig.getString("dcc.network.port")
      val internalDebuggerPort = 5005

      val nodeNumber = nodeName.replace("node", "").toInt
      val ip         = ipForNode(nodeNumber)

      val javaOptions = Option(System.getenv("CONTAINER_JAVA_OPTS")).getOrElse("")
      val configOverrides: String = {
        val ntpServer    = Option(System.getenv("NTP_SERVER")).fold("")(x => s"-Ddcc.ntp-server=$x ")
        val maxCacheSize = Option(System.getenv("MAX_CACHE_SIZE")).fold("")(x => s"-Ddcc.max-cache-size=$x ")

        var config = s"$javaOptions ${renderProperties(asProperties(overrides))} " +
          s"-Dlogback.stdout.level=TRACE -Dlogback.file.level=OFF -Ddcc.network.declared-address=$ip:$networkPort $ntpServer $maxCacheSize"

        // Debugger
        if (enableDebugger) config += s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$internalDebuggerPort "

        config
      }

      val profilerConfigEnv = if (enableProfiling) {
        // https://www.yourkit.com/docs/java/help/startup_options.jsp
        s"YOURKIT_OPTS=port=$ProfilerPort,listen=all,sampling,monitors,sessionname=DecentralChainNode,dir=$ContainerRoot/profiler,logdir=$ContainerRoot,onexit=snapshot"
      } else ""

      val debuggerPort = if (enableDebugger) Docker.freeDebuggerPort() else 0

      val portBindings = new Ports()
      if (enableDebugger) {
        portBindings.bind(ExposedPort.tcp(internalDebuggerPort), Ports.Binding.bindPort(debuggerPort))
      }

      val hostConfig = HostConfig.newHostConfig()
        .withPortBindings(portBindings)
        .withPublishAllPorts(true)
        .withNetworkMode(dccNetwork.getName)

      val envs = Seq(
        s"JAVA_OPTS=$configOverrides",
        profilerConfigEnv
      ).filter(_.nonEmpty)

      val exposedPorts = new java.util.ArrayList[ExposedPort]()
      exposedPorts.add(ExposedPort.tcp(internalDebuggerPort))
      if (Try(nodeConfig.getStringList("dcc.extensions").contains("com.decentralchain.events.BlockchainUpdates")).getOrElse(false)) {
        exposedPorts.add(ExposedPort.tcp(6881))
      }

      val containerId = {
        val jenkinsJobIdFromEnv = sys.env.get("JENKINS_JOB_ID").fold("")(s => s"-$s")
        val containerName       = s"${dccNetwork.getName}-$nodeName$jenkinsJobIdFromEnv"
        dumpContainers(
          client.listContainersCmd().withNameFilter(java.util.List.of(containerName)).exec(),
          "Containers with same name"
        )

        log.debug(s"Creating container $containerName at $ip with options: $javaOptions")
        val r = client.createContainerCmd(imageName)
          .withName(containerName)
          .withExposedPorts(exposedPorts)
          .withHostConfig(hostConfig)
          .withEnv(envs.asJava)
          .exec()
        Option(r.getWarnings).toSeq.flatMap(_.toSeq).foreach(log.warn(_))
        r.getId
      }

      // Reassign to specific IP (docker-java doesn't support NetworkingConfig on create)
      client.disconnectFromNetworkCmd()
        .withContainerId(containerId)
        .withNetworkId(dccNetwork.getId)
        .exec()
      client.connectToNetworkCmd()
        .withContainerId(containerId)
        .withNetworkId(dccNetwork.getId)
        .withContainerNetwork(new ContainerNetwork()
          .withIpv4Address(ip)
          .withIpamConfig(new ContainerNetwork.Ipam().withIpv4Address(ip)))
        .exec()

      client.startContainerCmd(containerId).exec()

      val node = new DockerNode(actualConfig, containerId, getNodeInfo(containerId, DCCSettings.fromRootConfig(actualConfig)))
      nodes.add(node)
      log.debug(s"Started $containerId -> ${node.name}: ${node.nodeInfo}${if (enableDebugger) s", debugger port = $debuggerPort" else ""}")
      node
    } catch {
      case NonFatal(e) =>
        log.error("Can't start a container", e)
        dumpContainers(client.listContainersCmd().exec())
        throw e
    }

  private def getNodeInfo(containerId: String, settings: DCCSettings): NodeInfo = {
    val restApiPort = settings.restAPISettings.port
    // assume test nodes always have an open port
    val networkPort = settings.networkSettings.derivedBindAddress.get.getPort

    val containerInfo  = inspectContainer(containerId)
    val dccIpAddress = containerInfo.getNetworkSettings.getNetworks.get(dccNetwork.getName).getIpAddress

    NodeInfo(restApiPort, networkPort, dccIpAddress, containerInfo.getNetworkSettings.getPorts)
  }

  @tailrec
  private def inspectContainer(containerId: String): com.github.dockerjava.api.command.InspectContainerResponse = {
    val containerInfo = client.inspectContainerCmd(containerId).exec()
    if (containerInfo.getNetworkSettings.getNetworks.asScala.contains(dccNetwork.getName)) containerInfo
    else {
      log.debug(s"Container $containerId has not connected to the network ${dccNetwork.getName} yet, retry")
      Thread.sleep(1000)
      inspectContainer(containerId)
    }
  }

  def stopContainer(node: DockerNode): String = {
    val id = node.containerId
    log.info(s"Stopping container with id: $id")
    client.stopContainerCmd(node.containerId).withTimeout(10).exec()
    saveProfile(node)
    saveLog(node)
    val containerInfo = client.inspectContainerCmd(node.containerId).exec()
    log.debug(s"""Container information for ${node.name}:
                 |Exit code: ${containerInfo.getState.getExitCodeLong}
                 |Error: ${containerInfo.getState.getError}
                 |Status: ${containerInfo.getState.getStatus}
                 |OOM killed: ${containerInfo.getState.getOOMKilled}""".stripMargin)
    id
  }

  def printThreadDump(node: DockerNode): Unit = {
    val id = node.containerId
    log.info(s"Saving thread dump for: $id")
    client.killContainerCmd(id).withSignal("SIGQUIT").exec()
  }

  def startContainer(id: String): Unit = {
    client.startContainerCmd(id).exec()
    nodes.asScala.find(_.containerId == id).foreach { node =>
      node.nodeInfo = getNodeInfo(node.containerId, node.settings)
      Await.result(node.waitForStartup(), 3.minutes)
    }
  }

  def killAndStartContainer(node: DockerNode): DockerNode = {
    val id = node.containerId
    log.info(s"Killing container with id: $id")
    client.killContainerCmd(id).withSignal("SIGINT").exec()
    saveProfile(node)
    saveLog(node)
    client.startContainerCmd(id).exec()
    node.nodeInfo = getNodeInfo(node.containerId, node.settings)
    Await.result(
      node.waitForStartup().flatMap(_ => connectToAll(node)),
      3.minutes
    )
    node
  }

  def restartNode(node: DockerNode, configUpdates: Config = empty): DockerNode = {
    Await.result(node.waitForHeightArise, 3.minutes)

    if (configUpdates != empty) {
      val renderedConfig = renderProperties(asProperties(configUpdates))

      // Docker do not allow updating ENV https://github.com/moby/moby/issues/8838 :(
      log.debug("Set new config directly in the entrypoint.sh script")
      val shPath = "/usr/share/dcc/bin/entrypoint.sh"
      val scriptCmd: Array[String] =
        Array("sh", "-c", s"sed -i 's|$${JAVA_OPTS}|$${JAVA_OPTS} $renderedConfig|' $shPath && cat $shPath")

      val execId = client.execCreateCmd(node.containerId).withCmd(scriptCmd*).exec().getId
      client.execStartCmd(execId).exec(new com.github.dockerjava.api.async.ResultCallback.Adapter[Frame]()).awaitCompletion()
    }

    restartContainer(node)
  }

  override def close(): Unit = {
    if (isStopped.compareAndSet(false, true)) {
      log.info("Stopping containers")

      nodes.asScala.foreach { node =>
        try {
          client.stopContainerCmd(node.containerId).withTimeout(if (enableProfiling) 60 else 0).exec()
          val exitCode = client.waitContainerCmd(node.containerId).start().awaitStatusCode()
          log.debug(s"Container ${node.name} stopped with exit status: $exitCode")
        } catch {
          case NonFatal(e) =>
            log.warn(s"Can't stop the container of ${node.name}", e)
        }

        try {
          saveLog(node)
          saveProfile(node)

          val containerInfo = client.inspectContainerCmd(node.containerId).exec()
          log.debug(s"""Container information for ${node.name}:
                       |Exit code: ${containerInfo.getState.getExitCodeLong}
                       |Error: ${containerInfo.getState.getError}
                       |Status: ${containerInfo.getState.getStatus}
                       |OOM killed: ${containerInfo.getState.getOOMKilled}""".stripMargin)
        } catch {
          case NonFatal(e) => log.warn(s"Can't save node logs: ${node.name}", e)
        }

        try {
          client.removeContainerCmd(node.containerId).exec()
        } catch {
          case NonFatal(e) => log.warn(s"Can't remove the container of ${node.name}", e)
        }
      }

      try {
        client.removeNetworkCmd(dccNetwork.getId).exec()
      } catch {
        case NonFatal(e) =>
          // https://github.com/moby/moby/issues/17217
          log.warn(s"Can not remove network ${dccNetwork.getName}", e)
      }

      http.close()
      dockerHttpClient.close()
      client.close()
    }
  }

  private def saveLog(node: DockerNode): Unit = {
    val containerId = node.containerId
    val logFile     = logDir().resolve(s"${node.name}.log").toFile
    log.info(s"Writing logs of $containerId to ${logFile.getAbsolutePath}")

    val fileStream = new FileOutputStream(logFile, false)
    try {
      client
        .logContainerCmd(containerId)
        .withFollowStream(true)
        .withStdOut(true)
        .withStdErr(true)
        .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter[Frame]() {
          override def onNext(frame: Frame): Unit = {
            try {
              fileStream.write(frame.getPayload)
            } catch {
              case _: IOException => // ignore write errors during log collection
            }
          }
        })
        .awaitCompletion()
    } finally {
      fileStream.close()
    }
  }

  private def saveProfile(node: DockerNode): Unit = if (enableProfiling) {
    try {
      val profilerDirStream = client.copyArchiveFromContainerCmd(node.containerId, ContainerRoot.resolve("profiler").toString).exec()

      try {
        val archiveStream = new ArchiveStreamFactory().createArchiveInputStream(ArchiveStreamFactory.TAR, profilerDirStream)
        val snapshotFile = Iterator
          .continually(Option(archiveStream.getNextEntry))
          .takeWhile(_.nonEmpty)
          .collectFirst {
            case Some(entry: TarArchiveEntry) if entry.isFile && entry.getName.contains(".snapshot") => entry
          }

        snapshotFile.foreach { archiveFile =>
          val output = new FileOutputStream(logDir().resolve(s"${node.name}.snapshot").toFile)
          try {
            IOUtils.copy(archiveStream, output)
            log.info(s"The snapshot of ${node.name} was successfully saved")
          } catch {
            case e: Throwable => throw new IOException(s"Can't copy ${archiveFile.getName} of ${node.name} to local fs", e)
          } finally {
            output.close()
          }
        }
      } catch {
        case e: Throwable => throw new IOException(s"Can't read a profiler directory stream of ${node.name}", e)
      } finally {
        Try(profilerDirStream.close())
      }
    } catch {
      case e: Throwable => log.warn(s"Can't save profiler logs of ${node.name}", e)
    }
  }

  def disconnectFromNetwork(node: DockerNode): Unit = disconnectFromNetwork(node.containerId)

  private def disconnectFromNetwork(containerId: String): Unit = {
    log.info(s"Trying to disconnect container $containerId from network ...")
    client.disconnectFromNetworkCmd()
      .withContainerId(containerId)
      .withNetworkId(dccNetwork.getId)
      .exec()
  }

  def restartContainer(node: DockerNode): DockerNode = {
    val id            = node.containerId
    val containerInfo = inspectContainer(id)
    val ports         = containerInfo.getNetworkSettings.getPorts
    log.info(s"New ports: ${ports.toString}")
    client.restartContainerCmd(id).withTimeout(10).exec()

    node.nodeInfo = Iterator
      .continually {
        Thread.sleep(1.second.toMillis)
        getNodeInfo(node.containerId, node.settings)
      }
      .dropWhile(_.ports.getBindings.isEmpty)
      .next()

    node.nodeInfo = getNodeInfo(node.containerId, node.settings)
    Await.result(
      node.waitForStartup().flatMap(_ => connectToAll(node)),
      3.minutes
    )
    node
  }

  def connectToNetwork(nodes: Seq[DockerNode]): Unit = {
    nodes.foreach(connectToNetwork)
    Await.result(Future.traverse(nodes)(connectToAll), 1.minute)
  }

  private def connectToNetwork(node: DockerNode): Unit = {
    log.info(s"Trying to connect node $node to network ...")
    val nodeNumber = node.name.replace("node", "").toInt
    val ip = ipForNode(nodeNumber)
    client.connectToNetworkCmd()
      .withContainerId(node.containerId)
      .withNetworkId(dccNetwork.getId)
      .withContainerNetwork(new ContainerNetwork()
        .withIpv4Address(ip)
        .withIpamConfig(new ContainerNetwork.Ipam().withIpv4Address(ip)))
      .exec()

    node.nodeInfo = getNodeInfo(node.containerId, node.settings)
    log.debug(s"New ${node.name} settings: ${node.nodeInfo}")
  }

  private def dumpContainers(containers: java.util.List[Container], label: String = "Containers"): Unit = {
    val x =
      if (containers.isEmpty) "No"
      else
        "\n" + containers.asScala
          .map { x =>
            s"Container(${x.getId}, status: ${x.getStatus}, names: ${x.getNames.mkString(", ")})"
          }
          .mkString("\n")

    log.debug(s"$label: $x")
  }

}

object Docker {
  val NodeImageName: String = "com.decentralchain/node-it:latest"

  private val ContainerRoot = Paths.get("/usr/share/dcc")
  private val ProfilerPort  = 10001

  private val RunId = Option(System.getenv("RUN_ID")).getOrElse(DateTimeFormatter.ofPattern("MM-dd--HH_mm_ss").format(LocalDateTime.now()))

  private val jsonMapper  = new ObjectMapper
  private val propsMapper = new JavaPropsMapper

  val configTemplate: Config   = parseResources("template.conf")
  val initialDccAmount: Long = configTemplate.getLong("dcc.blockchain.custom.genesis.initial-balance")

  def genesisOverride(featuresConfig: Option[Config] = None): Config = {
    // Starting a node and applying the genesis block takes a non-negligible amount of time. If we do not introduce an offset,
    // the system will treat the genesis block as if it was created in the past. In CI runs, this time gap can reach up
    // to 30 seconds.
    //
    // Block mining starts immediately after genesis is applied. As a result, there may be less time available for a
    // second block than some tests require (for example, to populate it with transactions).
    //
    // If the genesis block timestamp is slightly in the future, it will still be accepted. The only side effect is a
    // delayed start of mining.
    //
    // The chosen offset represents a compromise between realistic timing and test stability.
    val offsetMs        = 12_000
    val genesisTs: Long = System.currentTimeMillis() + offsetMs

    val timestampOverrides = parseString(s"""dcc.blockchain.custom.genesis {
                                            |  timestamp = $genesisTs
                                            |  block-timestamp = $genesisTs
                                            |  signature = null # To calculate it in Block.genesis
                                            |}""".stripMargin)

    val genesisConfig = timestampOverrides.withFallback(configTemplate)
    val gs            = ConfigSource.fromConfig(genesisConfig).at("dcc.blockchain.custom.genesis").loadOrThrow[GenesisSettings]
    val featuresConfigAdjusted = featuresConfig
      .map(_.withFallback(configTemplate))
      .getOrElse(configTemplate)
      .resolve()
    val features =
      ConfigSource
        .fromConfig(featuresConfigAdjusted)
        .at("dcc.blockchain.custom.functionality.pre-activated-features")
        .loadOrThrow[Map[Short, Int]]

    val isRideV6Activated          = features.get(BlockchainFeatures.RideV6.id).contains(0)
    val isTxStateSnapshotActivated = features.get(BlockchainFeatures.LightNode.id).contains(0)

    val genesisSignature = Block.genesis(gs, isRideV6Activated, isTxStateSnapshotActivated).explicitGet().id()

    parseString(s"dcc.blockchain.custom.genesis.signature = $genesisSignature").withFallback(timestampOverrides)
  }

  AddressScheme.current = new AddressScheme {
    override val chainId: Byte =
      ConfigSource.fromConfig(configTemplate).at("dcc.blockchain.custom.address-scheme-character").loadOrThrow[String].charAt(0).toByte
  }

  def apply(owner: Class[?]): Docker = new Docker(tag = owner.getSimpleName)

  private def asProperties(config: Config): Properties = {
    val jsonConfig = config.resolve().root().render(ConfigRenderOptions.concise())
    propsMapper.writeValueAsProperties(jsonMapper.readTree(jsonConfig))
  }

  private def renderProperties(p: Properties) =
    p.asScala
      .map {
        case (k, v) if v.contains(" ") => k -> s""""$v""""
        case x                         => x
      }
      .map { case (k, v) => s"-D$k=$v" }
      .mkString(" ")

  case class NodeInfo(restApiPort: Int, networkPort: Int, dccIpAddress: String, ports: Ports) {
    val nodeApiEndpoint: URL                       = URI.create(s"http://localhost:${externalPort(restApiPort)}").toURL
    val hostNetworkAddress: InetSocketAddress      = new InetSocketAddress("localhost", externalPort(networkPort))
    val containerNetworkAddress: InetSocketAddress = new InetSocketAddress(dccIpAddress, networkPort)

    def externalPort(internalPort: Int): Int = {
      val bindings = ports.getBindings.get(ExposedPort.tcp(internalPort))
      bindings(0).getHostPortSpec.toInt
    }
  }

  class DockerNode(config: Config, val containerId: String, private[Docker] var nodeInfo: NodeInfo) extends Node(config) {
    override def nodeExternalPort(internalPort: Int): Int = nodeInfo.externalPort(internalPort)

    override def nodeApiEndpoint: URL = nodeInfo.nodeApiEndpoint

    override val apiKey = "integration-test-rest-api"

    override def networkAddress: InetSocketAddress = nodeInfo.containerNetworkAddress

    def getConfig: Config = config

    override def networkAddressAccessibleFromHost: InetSocketAddress = nodeInfo.hostNetworkAddress
  }

  private val debuggerPort            = new AtomicInteger(11000)
  private def freeDebuggerPort(): Int = debuggerPort.getAndIncrement()
}
