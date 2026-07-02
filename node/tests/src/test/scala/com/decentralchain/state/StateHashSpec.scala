package com.decentralchain.state

import com.google.common.primitives.Longs
import com.decentralchain.account.Address
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.lang.v1.estimator.ScriptEstimatorV1
import com.decentralchain.state.StateHash.SectionId
import com.decentralchain.test.FreeSpec
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.transaction.smart.script.ScriptCompiler

class StateHashSpec extends FreeSpec {
  "state hash" - {
    val stateHash = new StateHashBuilder
    val address   = Address.fromString("3DckX66a9NEzR2cGuwHQ4ShAuF2ZSXUdGxB").explicitGet()
    val address1  = Address.fromString("3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH").explicitGet()
    val assetId   = IssuedAsset(ByteStr.decodeBase58("9ekQuYn92natMnMq8KqeGK3Nn7cpKd3BvPEGgD6fFyyz").get)
    val testScript = ScriptCompiler
      .compile(
        """
          |{-# STDLIB_VERSION 2 #-}
          |{-# CONTENT_TYPE EXPRESSION #-}
          |{-# SCRIPT_TYPE ACCOUNT #-}
          |true
          |""".stripMargin,
        ScriptEstimatorV1
      )
      .explicitGet()
      ._1
    val dataEntry    = StringDataEntry("test", "test")
    val dccAccount = TxHelpers.defaultSigner
    val blsAccount   = BlsKeyPair(dccAccount.privateKey)

    stateHash.addLeaseBalance(address, 10000L, 10000L)
    stateHash.addAccountScript(address, Some(testScript))
    stateHash.addAssetScript(assetId, Some(testScript))
    stateHash.addAlias(address, "test")
    stateHash.addAlias(address, "test1")
    stateHash.addAlias(address1, "test2")
    stateHash.addDataEntry(address, dataEntry)
    stateHash.addLeaseStatus(assetId.id, isActive = true)
    stateHash.addSponsorship(assetId, 1000)
    stateHash.addAssetBalance(address, assetId, 2000)
    stateHash.addAssetBalance(address1, assetId, 2000)
    stateHash.addDccBalance(address, 1000)
    stateHash.addNextCommittedGenerator(dccAccount.publicKey, blsAccount.publicKey)
    stateHash.addCommittedGeneratorBalances(Seq(3000))
    val result = stateHash.result()

    def hash(bs: Array[Byte]*): ByteStr    = ByteStr(com.decentralchain.crypto.fastHash(bs.reduce(_ ++ _)))
    def sect(id: SectionId.Value): ByteStr = result.hashes(id)
    import SectionId.*

    "sections" - {
      "lease balance" in {
        sect(LeaseBalance) shouldBe hash(
          address.bytes,
          Longs.toByteArray(10000L),
          Longs.toByteArray(10000L)
        )
      }

      "asset balance" in {
        // TreeMap entries are ordered by key bytes (address prefix here), and address1 < address.
        sect(AssetBalance) shouldBe hash(
          address1.bytes,
          assetId.id.arr,
          Longs.toByteArray(2000),
          address.bytes,
          assetId.id.arr,
          Longs.toByteArray(2000)
        )
      }

      "dcc balance" in {
        sect(DccBalance) shouldBe hash(
          address.bytes,
          Longs.toByteArray(1000)
        )
      }

      "account script" in {
        sect(AccountScript) shouldBe hash(
          address.bytes,
          testScript.bytes().arr
        )
      }

      "asset script" in {
        sect(AssetScript) shouldBe hash(
          assetId.id.arr,
          testScript.bytes().arr
        )
      }

      "alias" in {
        // TreeMap entries are ordered by key bytes (address prefix here), and address1 < address.
        sect(Alias) shouldBe hash(
          address1.bytes,
          "test2".getBytes(),
          address.bytes,
          "test".getBytes(),
          address.bytes,
          "test1".getBytes()
        )
      }

      "data entry" in {
        sect(DataEntry) shouldBe hash(
          address.bytes,
          "test".getBytes(),
          dataEntry.valueBytes
        )
      }

      "lease status" in {
        sect(LeaseStatus) shouldBe hash(
          assetId.id.arr,
          Array(1.toByte)
        )
      }

      "sponsor" in {
        sect(Sponsorship) shouldBe hash(
          assetId.id.arr,
          Longs.toByteArray(1000)
        )
      }

      "next generator" in {
        sect(NextCommittedGenerators) shouldBe hash(
          dccAccount.publicKey.arr,
          blsAccount.publicKey.byteStr.arr
        )
      }

      "committed generator balance" in {
        sect(CommittedGeneratorBalances) shouldBe hash(
          Longs.toByteArray(3000)
        )
      }
    }

    "total" in {
      val allHashes = StateHash.sections(true).map(id => result.hashes(id))
      allHashes shouldBe Seq(
        DccBalance,
        AssetBalance,
        DataEntry,
        AccountScript,
        AssetScript,
        LeaseBalance,
        LeaseStatus,
        Sponsorship,
        Alias,
        NextCommittedGenerators,
        CommittedGeneratorBalances
      ).map(sect)

      val testPrevHash = sect(SectionId.Alias)
      result.createStateHash(testPrevHash, true).totalHash shouldBe hash((testPrevHash.arr +: allHashes.map(_.arr))*)
      result.copy(hashes = result.hashes - SectionId.DccBalance).createStateHash(ByteStr.empty, true).totalHash shouldBe hash(
        (StateHashBuilder.EmptySectionHash.arr +: allHashes.tail.map(_.arr))*
      )
    }
  }
}
