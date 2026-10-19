package com.decentralchain.settings

import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions}
import pureconfig.{CamelCase, ConfigFieldMapping, KebabCase}

import java.io.File
import scala.compiletime.constValueTuple
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*

/** pureconfig silently ignores unknown keys, so a misspelt consensus key falls back to its default
  * (AUD-099). Unknown keys under the custom-chain `functionality` block are fatal; unknown keys in
  * rest-api / hotstuff / miner / blockchain-updates are logged.
  */
object UnknownConfigKeys {
  private val mapping = ConfigFieldMapping(CamelCase, KebabCase)

  private inline def keysOf[T](using m: Mirror.ProductOf[T]): Set[String] =
    constValueTuple[m.MirroredElemLabels].toList.map(l => mapping(l.toString)).toSet

  val FunctionalityKeys: Set[String] = keysOf[FunctionalitySettings]
  val RestApiKeys: Set[String]       = keysOf[RestAPISettings]
  val HotStuffKeys: Set[String]      = keysOf[HotStuffSettings]
  val MinerKeys: Set[String]         = keysOf[MinerSettings]
  // BlockchainUpdatesSettings lives in grpc-server, which node does not depend on: keep this in step with it.
  val BlockchainUpdatesKeys: Set[String] = Set("grpc-port", "bind-address", "min-keep-alive", "worker-threads")

  def unknown(config: Config, path: String, known: Set[String]): Set[String] =
    if (!config.hasPath(path)) Set.empty else config.getConfig(path).root().keySet().asScala.toSet -- known

  def check(config: Config): Either[String, Seq[String]] = {
    val fnPath   = "dcc.blockchain.custom.functionality"
    val isCustom = config.hasPath("dcc.blockchain.type") && config.getString("dcc.blockchain.type").equalsIgnoreCase("CUSTOM")
    val fatal    = if (isCustom) unknown(config, fnPath, FunctionalityKeys) else Set.empty[String]
    val warnings =
      Seq("dcc.rest-api" -> RestApiKeys, "dcc.hotstuff" -> HotStuffKeys, "dcc.miner" -> MinerKeys, "dcc.blockchain-updates" -> BlockchainUpdatesKeys)
        .flatMap { case (p, keys) =>
          unknown(config, p, keys).toSeq.sorted.map(k => s"Unknown config key $p.$k is ignored")
        }
    if (fatal.nonEmpty)
      Left(
        s"Unknown consensus config key(s) under $fnPath: ${fatal.toSeq.sorted.mkString(", ")}. Refusing to start: a misspelt consensus key would silently fall back to its default."
      )
    else Right(warnings)
  }

  /** `runMain com.decentralchain.settings.UnknownConfigKeys <file.conf>...`: validate config files offline. */
  def main(args: Array[String]): Unit = {
    val results = args.toSeq.map { f =>
      val parsed = ConfigFactory.parseFile(new File(f), ConfigParseOptions.defaults().setAllowMissing(false))
      f -> check(loadConfig(parsed))
    }
    results.foreach {
      case (f, Left(err))  => println(s"$f: FATAL $err")
      case (f, Right(Nil)) => println(s"$f: OK")
      case (f, Right(ws))  => ws.foreach(w => println(s"$f: WARN $w"))
    }
    if (results.exists(_._2.isLeft)) sys.exit(1)
  }
}
