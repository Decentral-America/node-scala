import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.*
import sbt.Keys.scalaVersion
import sbt.{Def, *}
import scalapb.compiler.Version.scalapbVersion

object Dependencies {
  // 4.2.16.Final patches CVE-2026-59901 (Bzip2Decoder infinite loop), CVE-2026-55831/55833
  // (HTTP codec), CVE-2026-56745 (SpdyHttpDecoder ByteBuf leak) -- all HIGH, on top of the
  // earlier CVE-2026-44249 patch already applied at 4.2.15.Final.
  private def nettyModule(module: String) = "io.netty" % s"netty-$module" % "4.2.16.Final"

  val gProtoVersion = "4.35.1"
  val gProto        = "com.google.protobuf" % "protobuf-java" % Dependencies.gProtoVersion
  val overrides     = Def.setting(
    Seq(
      "org.scala-lang"           %% "scala3-library" % scalaVersion.value,
      "com.google.code.gson"      % "gson"           % "2.14.0",
      "com.squareup.okio"         % "okio-jvm"       % "3.17.0",
      "org.apache.httpcomponents" % "httpclient"     % "4.5.14",
      "org.slf4j"                 % "slf4j-api"      % "2.0.18",
      "org.msgpack"               % "msgpack-core"   % "0.9.12",
      nettyModule("codec-http2"),
      nettyModule("codec-http"),
      nettyModule("handler-proxy"),
      nettyModule("codec-socks"),
      nettyModule("transport-native-unix-common"),
      nettyModule("resolver-dns"),
      jacksonModule("core", "core"),
      jacksonModule("core", "databind"),
      jacksonModule("datatype", "datatype-jdk8"),
      jacksonModule("datatype", "datatype-jsr310"),
      // Force tools.jackson.core 3.2.1 (fixes GHSA-2m67-wjpj-xhg9 HIGH CVE, 3.1.0 baseline;
      // transitive via pekko-http, document length constraint bypass) and GHSA-r7wm-3cxj-wff9
      // HIGH CVE (incomplete fix in the 3.2.0/2.22.0 baseline).
      "tools.jackson.core" % "jackson-core"     % "3.2.1",
      "tools.jackson.core" % "jackson-databind" % "3.2.1",
      gProto
    )
  )

  // Node protobuf schemas
  lazy val protoSchemasLib =
    "io.decentralchain" % "protobuf-schemas" % "1.6.5" classifier "protobuf-src" intransitive ()

  private def pekkoModule(module: String) = "org.apache.pekko" %% s"pekko-$module" % "1.6.0"

  private def pekkoHttpModule(module: String, version: String = "1.3.0") = "org.apache.pekko" %% module % version

  private def kamonModule(module: String) = "io.kamon" %% s"kamon-$module" % "2.8.1"

  // 2.22.1 fixes GHSA-r7wm-3cxj-wff9 HIGH CVE (incomplete fix in 2.22.0)
  private def jacksonModule(group: String, module: String, version: String = "2.22.1") =
    s"com.fasterxml.jackson.$group" % s"jackson-$module" % version

  private def web3jModule(module: String) = "org.web3j" % module % "6.0.0" // requires Java 21+; safe on JDK 25 (was 4.13.0)

  def monixModule(module: String): Def.Initialize[ModuleID] = Def.setting("io.monix" %%% s"monix-$module" % "3.4.1")

  private def grpcModule(module: String) = "io.grpc" % module % "1.82.1"

  val pekkoHttp       = pekkoHttpModule("pekko-http")
  val googleGuava     = "com.google.guava"    % "guava"             % "33.6.0-jre"
  val kamonCore       = kamonModule("core")
  val logback         = "ch.qos.logback"      % "logback-classic"   % "1.5.37"
  val asyncHttpClient = "org.asynchttpclient" % "async-http-client" % "3.0.11"
  val curve25519      = "io.decentralchain"   % "curve25519"        % "1.0.0"
  val nettyHandler    = nettyModule("handler")

  val playJson = "org.playframework" %% "play-json" % "3.0.6"

  val scalaTest   = "org.scalatest" %% "scalatest" % "3.2.20" % Test
  val scalaJsTest = Def.setting("com.lihaoyi" %%% "utest" % "0.9.5" % Test)

  private def sttp3Module(module: String) = "com.softwaremill.sttp.client3" %% module % "3.11.0"

  val sttp3      = sttp3Module("core")
  val sttp3Monix = sttp3Module("monix")

  val console = Seq("com.github.scopt" %% "scopt" % "4.1.0")

  def amazonCorretto(c: String): ModuleID = "software.amazon.cryptools" % "AmazonCorrettoCryptoProvider" % "2.5.0" classifier c

  val cryptoProviders = Seq(
    // Windows x86_64, Windows x86, macOS x86_64, linux x86_64
    "org.conscrypt" % "conscrypt-openjdk-uber" % "2.5.2",
    // macOS aarch64
    amazonCorretto("osx-aarch_64"),
    // fallback Java
    "org.bouncycastle" % "bcprov-jdk18on" % "1.84"
  )

  val lang = Def.setting(
    Seq(
      // defined here because %%% can only be used within a task or setting macro
      // explicit dependency can likely be removed when monix 3 is released
      monixModule("eval").value,
      "org.typelevel" %%% s"cats-core" % "2.13.0",
      "com.lihaoyi"   %%% "fastparse"  % "3.1.1",
      "org.typelevel" %%% "cats-mtl"   % "1.7.0",
      "ch.obermuhlner"  % "big-math"   % "2.3.2",
      googleGuava, // BaseEncoding.base16()
      curve25519,
      "io.decentralchain" % "groth16" % "0.2.1.0",
      web3jModule("crypto").excludeAll(ExclusionRule("org.bouncycastle", "bcprov-jdk15on")),
      protoSchemasLib % "protobuf"
    ) ++ cryptoProviders
  )

  lazy val scalapbRuntimeJS = Def.setting(
    Seq(
      "com.thesamet.scalapb" %%% "scalapb-runtime" % scalapbVersion,
      "com.thesamet.scalapb" %%% "scalapb-runtime" % scalapbVersion % "protobuf"
    )
  )

  lazy val it = scalaTest +: Seq(
    logback,
    "com.github.jnr"         % "jnr-unixsocket"                    % "0.39.1", // To support Apple ARM
    "com.github.docker-java" % "docker-java-core"                  % "3.7.1",
    "com.github.docker-java" % "docker-java-transport-httpclient5" % "3.7.1",
    jacksonModule("dataformat", "dataformat-properties", "2.22.1"),
    asyncHttpClient
  ).map(_ % Test)

  lazy val test = scalaTest +: Seq(
    logback,
    "org.scalatestplus" %% "scalacheck-1-19" % "3.2.20.0",
    "org.scalacheck"    %% "scalacheck"      % "1.19.0",
    "org.scalamock"     %% "scalamock"       % "7.5.5"
  ).map(_ % Test)

  lazy val logDeps = Seq(
    logback              % Runtime,
    pekkoModule("slf4j") % Runtime
  )

  // NOTE: rocksdbjni fat JAR issue (#13893) resolved in 10.5.1+; upgraded to match matcher
  private val rocksdb = "org.rocksdb" % "rocksdbjni" % "10.10.1.1"

  val scalaLogging: ModuleID = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6"
  lazy val node              = Def.setting(
    Seq(
      rocksdb,
      "commons-net"            % "commons-net"               % "3.13.0",
      "com.github.pureconfig" %% "pureconfig-core"           % "0.17.10",
      "com.github.pureconfig" %% "pureconfig-generic-scala3" % "0.17.10",
      "net.logstash.logback"   % "logstash-logback-encoder"  % "8.1" % Runtime, // 9.0 requires Jackson 3; stay on 8.1 (Jackson 2.x compatible)
      kamonCore,
      kamonModule("pekko-http"),
      kamonModule("executors"),
      "org.influxdb" % "influxdb-java" % "2.25",
      googleGuava,
      playJson,
      pekkoModule("actor"),
      pekkoModule("stream"),
      pekkoHttp,
      monixModule("reactive").value,
      nettyHandler,
      scalaLogging,
      "eu.timepit"        %% "refined"  % "0.11.4" exclude ("org.scala-lang.modules", "scala-xml_2.13"),
      "com.esaulpaugh"     % "headlong" % "13.3.1",
      "com.github.jbellis" % "jamm"     % "0.4.0", // Weighing caches
      web3jModule("abi").excludeAll(ExclusionRule("org.bouncycastle", "bcprov-jdk15on")),
      "io.decentralchain"              % "blst" % "0.3.16.0",
      amazonCorretto("linux-x86_64")   % Optional,
      amazonCorretto("linux-aarch_64") % Optional
    ) ++ console ++ logDeps ++ protobuf.value
  )

  lazy val nodeTests = Seq(
    pekkoModule("testkit"),
    pekkoHttpModule("pekko-http-testkit")
  ) ++ test ++ logDeps

  lazy val scalapbRuntime = Def.setting(
    Seq(
      gProto,
      gProto % "protobuf"
    )
  )

  lazy val protobuf = Def.setting {
    scalapbRuntime.value :+ protoSchemasLib % "protobuf"
  }

  lazy val grpc: Seq[ModuleID] = Seq(
    grpcModule("grpc-netty"),
    grpcModule("grpc-services"),
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapbVersion,
    protoSchemasLib         % "protobuf"
  )

  lazy val rideRunner = Def.setting(
    Seq(
      rocksdb,
      "com.github.ben-manes.caffeine" % "caffeine"                 % "3.2.4",
      "net.logstash.logback"          % "logstash-logback-encoder" % "8.1" % Runtime, // 9.0 requires Jackson 3; stay on 8.1 (Jackson 2.x compatible)
      kamonModule("caffeine"),
      kamonModule("prometheus"),
      sttp3,
      sttp3Monix,
      "org.scala-lang.modules"             %% "scala-xml"              % "2.4.0", // JUnit reports
      pekkoHttpModule("pekko-http-testkit") % Test,
      "com.softwaremill.diffx"             %% "diffx-core"             % "0.9.0" % Test,
      "com.softwaremill.diffx"             %% "diffx-scalatest-should" % "0.9.0" % Test,
      grpcModule("grpc-inprocess")          % Test
    ) ++ Dependencies.console ++ Dependencies.logDeps ++ Dependencies.test
  )

  lazy val circe = Def.setting {
    val circeVersion = "0.14.16"
    Seq(
      "io.circe" %%% "circe-core",
      "io.circe" %%% "circe-generic",
      "io.circe" %%% "circe-parser"
    ).map(_ % circeVersion)
  }

  // https://github.com/sbt/sbt-javaagent#scopes
  // dist (only sbt-native-packager), because causes using logs before needed, so System.setProperty in RideRunnerWithPreparedStateApp has no effect.
  lazy val kanela =
    Seq("io.kamon" % "kanela-agent" % "2.0.0" % "dist")
}
