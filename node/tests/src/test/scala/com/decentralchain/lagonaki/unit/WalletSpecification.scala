package com.decentralchain.lagonaki.unit

import java.io.File
import java.nio.file.Files

import cats.syntax.option.*
import com.decentralchain.common.state.ByteStr
import com.decentralchain.settings.WalletSettings
import com.decentralchain.test.FunSuite
import com.decentralchain.wallet.Wallet

class WalletSpecification extends FunSuite {

  private val walletSize = 10
  val w                  = Wallet(WalletSettings(None, "cookies".some, ByteStr.decodeBase58("FQgbSAm6swGbtqA3NE8PttijPhT4N3Ufh4bHFAkyVnQz").toOption))

  test("wallet - acc creation") {
    w.generateNewAccounts(walletSize)

    w.privateKeyAccounts.size shouldBe walletSize
    w.privateKeyAccounts.map(_.toAddress.toString) shouldBe Seq(
      "3DeYD7Nx5DwMa9atximvtPfWTwu2cHFoiL5",
      "3DTqq11u5KjZWarsHLob4GTAgsfR7C4sqzV",
      "3DiL3zCzEyucpc71N4oY2YkTWfPksRSpaw7",
      "3DPhrNsnRMPp4wWbCnasPzaHGfmnKPLSHsy",
      "3DTaFr3JuS7eNuLMnisGuqs5bZk6yQQDoqV",
      "3DXnQDXMWrwPPsX5sH6Wjg9gzQDZ82L3bwA",
      "3DT5FnyoBG5XkFHDv4hjxkZnFU6AbGDDHif",
      "3Dbez8MDfXLmw4qDgYNKXCKxrQtR8S2HxUG",
      "3DPGHG1F21F4347MfzzhSEZgoupT4eSC5Fb",
      "3DZJUpDEMykJsfc1UNVDYnesXaumoob6LXG"
    )
  }

  test("wallet - acc deletion") {

    val head = w.privateKeyAccounts.head
    w.deleteAccount(head)
    assert(w.privateKeyAccounts.lengthCompare(walletSize - 1) == 0)

    w.deleteAccount(w.privateKeyAccounts.head)
    assert(w.privateKeyAccounts.lengthCompare(walletSize - 2) == 0)

    w.privateKeyAccounts.foreach(w.deleteAccount)

    assert(w.privateKeyAccounts.isEmpty)
  }

  test("reopening") {
    val walletFile = Some(createTestTemporaryFile("wallet", ".dat"))

    val w1 = Wallet(WalletSettings(walletFile, "cookies".some, ByteStr.decodeBase58("FQgbSAm6swGbtqA3NE8PttijPhT4N3Ufh4bHFAkyVnQz").toOption))
    w1.generateNewAccounts(10)
    val w1PrivateKeys = w1.privateKeyAccounts
    val w1nonce       = w1.nonce

    val w2 = Wallet(WalletSettings(walletFile, "cookies".some, None))
    w2.privateKeyAccounts.nonEmpty shouldBe true
    w2.privateKeyAccounts shouldEqual w1PrivateKeys
    w2.nonce shouldBe w1nonce

    val seedError = intercept[IllegalArgumentException](Wallet(WalletSettings(walletFile, "cookies".some, ByteStr.decodeBase58("fake").toOption)))
    seedError.getMessage should include("Seed from config doesn't match the actual seed")
  }

  test("reopen with incorrect password") {
    val file = Some(createTestTemporaryFile("wallet", ".dat"))
    val w1   = Wallet(WalletSettings(file, "password".some, ByteStr.decodeBase58("FQgbSAm6swGbtqA3NE8PttijPhT4N3Ufh4bHFAkyVnQz").toOption))
    w1.generateNewAccounts(3)

    assertThrows[IllegalArgumentException] {
      Wallet(WalletSettings(file, "incorrect password".some, None))
    }
  }

  def createTestTemporaryFile(name: String, ext: String): File = {
    val file = Files.createTempFile(name, ext).toFile
    file.deleteOnExit()

    file
  }
}
