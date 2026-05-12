package com.decentralchain

import java.nio.file.Files

import com.decentralchain.database.RDB
import com.decentralchain.db.DBCacheSettings
import com.decentralchain.events.BlockchainUpdateTriggers
import org.scalatest.{BeforeAndAfterEach, Suite}

trait WithNewDBForEachTest extends BeforeAndAfterEach with DBCacheSettings {
  this: Suite =>

  private val path                   = Files.createTempDirectory(s"rocks-${getClass.getSimpleName}").toAbsolutePath
  private var currentDBInstance: RDB = compiletime.uninitialized

  protected val ignoreBlockchainUpdateTriggers: BlockchainUpdateTriggers = BlockchainUpdateTriggers.noop

  def db: RDB = currentDBInstance

  override def beforeEach(): Unit = {
    currentDBInstance = RDB.open(dbSettings.copy(directory = path.toAbsolutePath.toString))
    super.beforeEach()
  }

  override def afterEach(): Unit =
    try {
      super.afterEach()
      db.close()
    } finally {
      TestHelpers.deleteRecursively(path)
    }
}
