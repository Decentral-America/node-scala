package com.decentralchain.api.grpc.test

import com.google.protobuf.ByteString
import com.decentralchain.account.KeyPair
import com.decentralchain.api.grpc.{AssetsApiGrpcImpl}
import io.decentralchain.api.grpc.{AssetInfoResponse, NFTRequest, NFTResponse}
import com.decentralchain.block.Block.ProtoBlockVersion
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.test.FreeSpec
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.utils.{DiffMatchers, Schedulers}
import monix.execution.ExecutionModel.SynchronousExecution
import monix.execution.Scheduler
import org.scalatest.BeforeAndAfterAll

class AssetsApiGrpcSpec extends FreeSpec with BeforeAndAfterAll with DiffMatchers with WithDomain with GrpcApiHelpers {
  private given scheduler: Scheduler = Schedulers.singleThread("grpc", executionModel = SynchronousExecution)
  val sender: KeyPair                = TxHelpers.signer(1)

  "GetNFTList should work" in withDomain(RideV6.addFeatures(BlockchainFeatures.ReduceNFTFee), AddrWithBalance.enoughBalances(sender)) { d =>
    val grpcApi = getGrpcApi(d)

    val nftIssues = (1 to 10).map(idx => TxHelpers.issue(sender, 1, name = s"nft$idx", reissuable = false))

    d.appendBlock(nftIssues*)

    d.liquidAndSolidAssert { () =>
      val (observer, result) = createObserver[NFTResponse]
      grpcApi.getNFTList(
        NFTRequest.of(ByteString.copyFrom(sender.toAddress.bytes), 10, ByteString.EMPTY),
        observer
      )
      result.runSyncUnsafe() shouldBe nftIssues.zipWithIndex.map { case (nftTx, i) =>
        NFTResponse.of(
          ByteString.copyFrom(nftTx.asset.id.arr),
          Some(
            AssetInfoResponse.of(
              ByteString.copyFrom(sender.publicKey.arr),
              nftTx.name.toStringUtf8,
              nftTx.description.toStringUtf8,
              nftTx.decimals.value,
              nftTx.reissuable,
              nftTx.quantity.value,
              None,
              0L,
              0L,
              None,
              sequenceInBlock = i + 1,
              issueHeight = 2
            )
          )
        )
      }
    }
  }

  "NODE-999. GetNftList limit should work properly" in withDomain(
    RideV6.addFeatures(BlockchainFeatures.ReduceNFTFee),
    AddrWithBalance.enoughBalances(sender)
  ) { d =>
    val nftIssues = (1 to 5).map(idx => TxHelpers.issue(sender, 1, name = s"nft$idx", reissuable = false))
    val limit     = 2
    val afterId   = 1 // second element

    d.appendBlock()
    val mb1 = d.appendMicroBlock(nftIssues.take(afterId + 1)*)
    d.appendMicroBlock(nftIssues.drop(afterId + 1)*)

    // full liquid
    d.rocksDBWriter.containsTransaction(nftIssues(afterId)) shouldBe false
    d.rocksDBWriter.containsTransaction(nftIssues(afterId + 1)) shouldBe false
    check()

    // liquid afterId
    d.appendBlock(d.createBlock(ProtoBlockVersion, nftIssues.drop(afterId + 1), Some(mb1)))
    d.rocksDBWriter.containsTransaction(nftIssues(afterId)) shouldBe true
    d.rocksDBWriter.containsTransaction(nftIssues(afterId + 1)) shouldBe false
    check()

    // full solid
    d.appendBlock()
    d.rocksDBWriter.containsTransaction(nftIssues(afterId)) shouldBe true
    d.rocksDBWriter.containsTransaction(nftIssues(afterId + 1)) shouldBe true
    check()

    def check() = {
      val (observer, result) = createObserver[NFTResponse]
      val request            = NFTRequest.of(
        ByteString.copyFrom(sender.toAddress.bytes),
        limit,
        afterAssetId = ByteString.copyFrom(nftIssues(afterId).asset.id.arr)
      )
      getGrpcApi(d).getNFTList(request, observer)
      val response = result.runSyncUnsafe()
      response.size shouldBe limit
      response.map(_.assetInfo.get.name) shouldBe nftIssues.slice(afterId + 1, afterId + limit + 1).map(_.name.toStringUtf8)
    }
  }

  private def getGrpcApi(d: Domain) =
    new AssetsApiGrpcImpl(d.assetsApi, d.accountsApi)
}
