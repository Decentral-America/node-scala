package com.decentralchain

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}

import com.decentralchain.account.Address
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.settings.{FunctionalitySettings, GenesisSettings, GenesisTransactionSettings, DCCSettings}

import scala.concurrent.duration.*

object TestHelpers {
  def genesisSettings(balances: Map[Address, Long], blockTimestamp: Long = System.currentTimeMillis()): GenesisSettings = {
    val totalAmount  = balances.values.sum
    val transactions = balances.map { case (account, amount) =>
      GenesisTransactionSettings(account.toString, amount)
    }.toSeq

    GenesisSettings(blockTimestamp, blockTimestamp, totalAmount, None, transactions, 1000, 60.seconds)
  }

  def enableNG(settings: FunctionalitySettings): FunctionalitySettings =
    settings.copy(blockVersion3AfterHeight = 0, preActivatedFeatures = settings.preActivatedFeatures ++ Map(BlockchainFeatures.NG.id -> 0))

  def enableNG(settings: DCCSettings): DCCSettings =
    settings.copy(
      blockchainSettings = settings.blockchainSettings.copy(functionalitySettings = enableNG(settings.blockchainSettings.functionalitySettings))
    )

  def deleteRecursively(path: Path): Unit = Files.walkFileTree(
    path,
    new SimpleFileVisitor[Path] {
      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
        Option(exc).fold {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        }(throw _)
      }

      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.delete(file)
        FileVisitResult.CONTINUE
      }
    }
  )
}
