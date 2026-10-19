package com.decentralchain.tools

import com.decentralchain.account.{Address, AddressScheme}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.explicitGet
import com.decentralchain.common.utils.{Base58, Base64}
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.v1.compiler.Terms.CONST_BYTESTR
import com.decentralchain.transaction.{Transaction, TxHelpers, TxVersion}
import play.api.libs.json.Json

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.security.SecureRandom

/** Live check that RIDE `groth16Verify` works on the network (audit AUD-004). Run ONLY after every node
  * runs an image whose `UtilApp smoke` passed: a node without the native library cannot validate the invoke.
  *   new-seed <file>            write a random throwaway seed (mode 0600), print its address
  *   run <seedFile> <nodeUrl>   set a one-callable dApp on that account, invoke groth16Verify with a
  *                              known-valid vector, require the dApp's data entry "groth16" == true
  *
  * The throwaway account needs a little DCC for fees. If the environment variable GROTH16_FUNDER_SEED holds
  * the seed of a funded account, `run` first transfers 0.1 DCC from it; otherwise fund the printed address.
  */
object Groth16LiveCheck {
  // Same vector as lang IntegrationTest "groth16Verify" (expected true) and UtilApp smoke.
  val Vk: String =
    "hwk883gUlTKCyXYA6XWZa8H9/xKIYZaJ0xEs0M5hQOMxiGpxocuX/8maSDmeCk3bo5ViaDBdO7ZBxAhLSe5k/5TFQyF5Lv7KN2tLKnwgoWMqB16OL8WdbePIwTCuPtJNAFKoTZylLDbSf02kckMcZQDPF9iGh+JC99Pio74vDpwTEjUx5tQ99gNQwxULtztsqDRsPnEvKvLmsxHt8LQVBkEBm2PBJFY+OXf1MNW021viDBpR10mX4WQ6zrsGL5L0GY4cwf4tlbh+Obit+LnN/SQTnREf8fPpdKZ1sa/ui3pGi8lMT6io4D7Ujlwx2RdCkBF+isfMf77HCEGsZANw0hSrO2FGg14Sl26xLAIohdaW8O7gEaag8JdVAZ3OVLd5Df1NkZBEr753Xb8WwaXsJjE7qxwINL1KdqA4+EiYW4edb7+a9bbBeOPtb67ZxmFqgyTNS/4obxahezNkjk00ytswsENg//Ee6dWBJZyLH+QGsaU2jO/W4WvRyZhmKKPdipOhiz4Rlrd2XYgsfHsfWf5v4GOTL+13ZB24dW1/m39n2woJ+v686fXbNW85XP/r"
  val Proof: String =
    "lvQLU/KqgFhsLkt/5C/scqs7nWR+eYtyPdWiLVBux9GblT4AhHYMdCgwQfSJcudvsgV6fXoK+DUSRgJ++Nqt+Wvb7GlYlHpxCysQhz26TTu8Nyo7zpmVPH92+UYmbvbQCSvX2BhWtvkfHmqDVjmSIQ4RUMfeveA1KZbSf999NE4qKK8Do+8oXcmTM4LZVmh1rlyqznIdFXPN7x3pD4E0gb6/y69xtWMChv9654FMg05bAdueKt9uA4BEcAbpkdHF"
  val Inputs: String = "LcMT3OOlkHLzJBKCKjjzzVMg+r+FVgd52LlhZPB4RFg="

  val DApp: String =
    """{-# STDLIB_VERSION 6 #-}
      |{-# CONTENT_TYPE DAPP #-}
      |{-# SCRIPT_TYPE ACCOUNT #-}
      |
      |@Callable(i)
      |func check(vk: ByteVector, proof: ByteVector, inputs: ByteVector) = [BooleanEntry("groth16", groth16Verify(vk, proof, inputs))]
      |""".stripMargin

  private val ChainId: Byte = '!'.toByte
  private val http          = HttpClient.newHttpClient()

  private def get(url: String): HttpResponse[String] =
    http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

  private def post(url: String, contentType: String, body: String): String = {
    val r = http.send(
      HttpRequest.newBuilder(URI.create(url)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
      HttpResponse.BodyHandlers.ofString()
    )
    require(r.statusCode() == 200, s"POST $url -> ${r.statusCode()}: ${r.body().take(500)}")
    r.body()
  }

  private def broadcastAndWait(node: String, tx: Transaction): Unit = {
    post(s"$node/transactions/broadcast", "application/json", tx.json().toString)
    val deadline = System.currentTimeMillis() + 180000
    while (get(s"$node/transactions/info/${tx.id()}").statusCode() != 200) {
      require(System.currentTimeMillis() < deadline, s"${tx.id()} not confirmed within 180 s")
      Thread.sleep(3000)
    }
    println(s"confirmed ${tx.id()}")
  }

  private def useTestnetScheme(): Unit = AddressScheme.current = new AddressScheme { val chainId: Byte = ChainId }

  def main(args: Array[String]): Unit = args.toList match {
    case "new-seed" :: file :: Nil =>
      useTestnetScheme()
      val bytes = new Array[Byte](32)
      new SecureRandom().nextBytes(bytes)
      val seed = Base58.encode(bytes)
      val p    = Path.of(file)
      Files.writeString(p, seed, UTF_8)
      Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"))
      println(Address.fromPublicKey(EquivocationDrill.accountOf(seed).publicKey))

    case "run" :: seedFile :: node :: Nil =>
      useTestnetScheme()
      val acc  = EquivocationDrill.accountOf(Files.readString(Path.of(seedFile), UTF_8).trim)
      val dApp = Address.fromPublicKey(acc.publicKey)
      sys.env.get("GROTH16_FUNDER_SEED").filter(_.nonEmpty).foreach { funderSeed =>
        val funder = EquivocationDrill.accountOf(funderSeed)
        broadcastAndWait(node, TxHelpers.transfer(funder, dApp, amount = 10000000L, fee = 100000L, chainId = ChainId))
      }
      val compiled = (Json.parse(post(s"$node/utils/script/compileCode", "text/plain", DApp)) \ "script").as[String]
      val script   = Script.fromBase64String(compiled.stripPrefix("base64:")).explicitGet()
      broadcastAndWait(node, TxHelpers.setScript(acc, script, fee = 1000000L, version = TxVersion.V2, chainId = ChainId))
      val callArgs = Seq(Vk, Proof, Inputs).map(b => CONST_BYTESTR(ByteStr(Base64.decode(b))).explicitGet())
      broadcastAndWait(node, TxHelpers.invoke(dApp, Some("check"), callArgs, invoker = acc, fee = 1000000L))
      val entry = Json.parse(get(s"$node/addresses/data/$dApp/groth16").body())
      println(s"dApp $dApp data entry: $entry")
      require((entry \ "value").asOpt[Boolean].contains(true), "groth16Verify did not return true on the live chain")
      println("groth16 live check: OK")

    case other => sys.error(s"usage: new-seed <file> | run <seedFile> <nodeUrl>; got $other")
  }
}
