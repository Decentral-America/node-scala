package com.decentralchain.db
import com.typesafe.config.ConfigFactory
import com.decentralchain.settings.DCCSettings

trait DBCacheSettings {
  lazy val dbSettings        = DCCSettings.fromRootConfig(ConfigFactory.load()).dbSettings
  lazy val maxCacheSize: Int = dbSettings.maxCacheSize
}
