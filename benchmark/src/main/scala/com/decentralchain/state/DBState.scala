package com.decentralchain.state

import com.decentralchain.Application
import com.decentralchain.account.AddressScheme
import com.decentralchain.common.state.ByteStr
import com.decentralchain.database.{RDB, RocksDBWriter}
import com.decentralchain.lang.directives.DirectiveSet
import com.decentralchain.settings.DCCSettings
import com.decentralchain.transaction.smart.DCCEnvironment
import com.decentralchain.utils.ScorexLogging
import monix.eval.Coeval
import org.openjdk.jmh.annotations.{Param, Scope, State, TearDown}

import java.io.File

@State(Scope.Benchmark)
abstract class DBState extends ScorexLogging {
  @Param(Array("waves.conf"))
  var configFile = ""

  lazy val settings: DCCSettings = Application.loadApplicationConfig(Some(new File(configFile)).filter(_.exists()))

  lazy val rdb: RDB = RDB.open(settings.dbSettings)

  lazy val rocksDBWriter: RocksDBWriter = RocksDBWriter(
    rdb,
    settings.blockchainSettings,
    settings.dbSettings.copy(maxCacheSize = 1),
    settings.enableLightMode
  )

  AddressScheme.current = new AddressScheme { override val chainId: Byte = '?' }

  lazy val environment = DCCEnvironment(
    AddressScheme.current.chainId,
    Coeval.raiseError(new NotImplementedError("`tx` is not implemented")),
    Coeval(rocksDBWriter.height),
    rocksDBWriter,
    null,
    DirectiveSet.contractDirectiveSet,
    ByteStr.empty
  )

  @TearDown
  def close(): Unit = {
    rocksDBWriter.close()
    rdb.close()
  }
}
