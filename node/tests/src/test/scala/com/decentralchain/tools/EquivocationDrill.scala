package com.decentralchain.tools

import com.decentralchain.account.{Address, AddressScheme, KeyPair, SeedKeyPair}
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.HotStuffQuorum
import com.decentralchain.crypto
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.network.client.NetworkSender
import com.decentralchain.network.{HotStuffVote, TrafficLogger}
import com.decentralchain.state.{GenerationPeriod, Height}
import com.decentralchain.transaction.CommitToGenerationTransaction
import com.google.common.primitives.Ints
import io.decentralchain.protobuf.block.HotStuffPhase
import play.api.libs.json.{JsArray, Json}

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

/** Testnet fault-injection tool for the HotStuff equivocation drill (TESTNET-FINAL-PLAN Task 6.5).
  *
  * Our nodes are honest, so the drill needs something that deliberately double-votes: two validly
  * signed HotStuff votes from ONE committee member for two different blocks in the same
  * (view, phase). Peers must detect the pair, a slashing-enabled miner must fold the proof into a
  * block, and the offender must forfeit its generation deposit. Test-scope only; never shipped.
  *
  * Run (from node-scala): sbt "node-tests/Test/runMain com.decentralchain.tools.EquivocationDrill <cmd> ..."
  *   address    <seedFile> <chainId>
  *   commit     <seedFile> <nodeUrl>                      commit the offender for the next generation period
  *   equivocate <seedFile> <nodeUrl> <p2pHost:port>       send the conflicting vote pair over P2P
  */
object EquivocationDrill {

  /** The account a TS-SDK / node wallet derives from `seed` at nonce 0. */
  def accountOf(seed: String): SeedKeyPair =
    KeyPair(crypto.secureHash(Ints.toByteArray(0) ++ seed.getBytes(UTF_8)))

  /** Two votes from `voter` (committee index `voterIndex`) for the same (view, PREPARE) but different
    * blocks, each signed exactly like HotStuffCoordinator.castVotes signs an honest vote.
    */
  def equivocatingVotes(
      voter: KeyPair,
      voterIndex: Int,
      view: Int,
      height: Int,
      epoch: Int,
      blockA: BlockId,
      blockB: BlockId
  ): (HotStuffVote, HotStuffVote) = {
    require(blockA != blockB, "an equivocation needs two different blocks")
    val bls                  = BlsKeyPair(voter.privateKey)
    val phase                = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
    def vote(block: BlockId) = {
      val sig = bls.sign(HotStuffQuorum.voteMessage(view, phase, block, height, epoch), HotStuffQuorum.VoteDst)
      HotStuffVote(view, phase, block, Height(height), voterIndex, sig.byteStr, epoch)
    }
    (vote(blockA), vote(blockB))
  }

  private val http = HttpClient.newHttpClient()

  private def get(url: String): String = {
    val r = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
    require(r.statusCode() == 200, s"GET $url -> ${r.statusCode()}: ${r.body().take(300)}")
    r.body()
  }

  private def post(url: String, body: String): String = {
    val r = http.send(
      HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
      HttpResponse.BodyHandlers.ofString()
    )
    require(r.statusCode() == 200, s"POST $url -> ${r.statusCode()}: ${r.body().take(500)}")
    r.body()
  }

  private def height(node: String): Int = (Json.parse(get(s"$node/blocks/height")) \ "height").as[Int]

  private def periodLength(node: String): Int = (Json.parse(get(s"$node/node/status")) \ "generationPeriodLength").as[Int]

  private def finalityActivation(node: String): Int =
    (Json.parse(get(s"$node/activation/status")) \ "features")
      .as[JsArray]
      .value
      .find(f => (f \ "id").as[Int] == 25)
      .flatMap(f => (f \ "activationHeight").asOpt[Int])
      .getOrElse(sys.error("Deterministic Finality (feature 25) is not active"))

  private def periodOf(node: String, h: Int): GenerationPeriod =
    GenerationPeriod.from(Height(h), Height(finalityActivation(node)), periodLength(node)).getOrElse(sys.error(s"no generation period for $h"))

  private def readSeed(file: String): String = new String(Files.readAllBytes(Path.of(file)), UTF_8).trim

  def main(args: Array[String]): Unit = args.toList match {
    case "address" :: seedFile :: chainIdText :: Nil =>
      val scheme = chainIdArg(chainIdText)
      AddressScheme.current = new AddressScheme { val chainId: Byte = scheme }
      println(Address.fromPublicKey(accountOf(readSeed(seedFile)).publicKey))

    case "commit" :: seedFile :: node :: Nil =>
      val chainId = '!'.toByte
      AddressScheme.current = new AddressScheme { val chainId: Byte = '!'.toByte }
      val account = accountOf(readSeed(seedFile))
      val bls     = BlsKeyPair(account.privateKey)
      val start   = periodOf(node, height(node)).next.start
      val pop     = CommitToGenerationTransaction.mkPopSignature(bls, start, account.publicKey, chainId)
      val tx      = CommitToGenerationTransaction
        .selfSigned(1.toByte, account, bls.publicKey, start, System.currentTimeMillis(), 10_000_000L, pop, chainId)
        .fold(e => sys.error(s"build commit: $e"), identity)
      println(s"offender=${Address.fromPublicKey(account.publicKey)} committing for period starting $start, tx=${tx.id()}")
      println(post(s"$node/transactions/broadcast", tx.json().toString))

    case "equivocate" :: seedFile :: node :: p2p :: Nil =>
      AddressScheme.current = new AddressScheme { val chainId: Byte = '!'.toByte }
      val account = accountOf(readSeed(seedFile))
      val me      = Address.fromPublicKey(account.publicKey).toString
      // Settled height, as honest replicas vote one height behind the tip.
      val h          = height(node) - 1
      val committee  = Json.parse(get(s"$node/generators/at/$h")).as[JsArray].value.map(g => (g \ "address").as[String]).toVector
      val voterIndex = committee.indexOf(me)
      require(voterIndex >= 0, s"$me is not in the committee at height $h: $committee")
      val epoch  = periodOf(node, h).index
      val blockA = ByteStr.decodeBase58((Json.parse(get(s"$node/blocks/headers/at/$h")) \ "id").as[String]).get
      val blockB = ByteStr(crypto.secureHash(blockA.arr ++ "equivocation-drill".getBytes(UTF_8)))
      val (a, b) = equivocatingVotes(account, voterIndex, view = h, height = h, epoch = epoch, blockA, blockB)
      println(s"offender=$me index=$voterIndex view=$h epoch=$epoch blockA=$blockA blockB=$blockB")

      given ExecutionContext = ExecutionContext.global
      val Array(host, port)  = p2p.split(':')
      val sender             = new NetworkSender(TrafficLogger.Settings(Set.empty, Set.empty), '!', "equivocation-drill", System.nanoTime())
      try {
        val ch = Await.result(sender.connect(new InetSocketAddress(host, port.toInt)), 30.seconds)
        require(ch.isActive, s"P2P channel to $p2p is not active")
        Await.result(sender.send(ch, a, b), 30.seconds)
        println(s"sent conflicting PREPARE votes for view $h to $p2p")
      } finally sender.close()

    case other =>
      System.err.println(
        s"usage: address <seedFile> <chainId> | commit <seedFile> <nodeUrl> | equivocate <seedFile> <nodeUrl> <p2pHost:port>; got $other"
      )
      sys.exit(2)
  }

  private def chainIdArg(s: String): Byte = s.headOption.getOrElse(sys.error("chainId")).toByte
}
