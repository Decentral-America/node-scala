package com.decentralchain.state.snapshot

import com.google.common.primitives.Ints
import com.google.protobuf.ByteString.copyFrom as bs
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base64
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.crypto.fastHash
import com.decentralchain.lang.directives.values.V6
import com.decentralchain.lang.v1.compiler.TestCompiler
import io.decentralchain.protobuf.snapshot.{TransactionStatus, TransactionStateSnapshot as TSS}
import io.decentralchain.protobuf.transaction.DataEntry
import io.decentralchain.protobuf.{Amount, PBSnapshots}
import com.decentralchain.state.*
import com.decentralchain.test.*
import com.decentralchain.transaction.TxHelpers
import org.bouncycastle.util.encoders.Hex

class TxStateSnapshotHashSpec extends PropSpec {
  private def hashInt(i: Int) = bs(fastHash(Ints.toByteArray(i)))

  val stateHash         = new StateHashBuilder
  private val signer101 = TxHelpers.signer(101)
  private val signer102 = TxHelpers.signer(102)
  private val signer103 = TxHelpers.signer(103)

  private val address1 = signer101.toAddress
  private val address2 = signer102.toAddress
  private val address3 = signer103.toAddress

  private val assetId1 = hashInt(0xaa22aa44)
  private val assetId2 = hashInt(0xbb22aa44)

  private val leaseId  = hashInt(0x11aaef22)
  private val orderId1 = hashInt(0xee23ef22)
  private val orderId2 = hashInt(0xbb77ef29)

  private val testScript = bs(TestCompiler(V6).compileExpression("true").bytes().arr)

  private val dccBalances = TSS(balances =
    Seq(
      TSS.Balance(bs(address1.bytes), Some(Amount(amount = 10.dcc))),
      TSS.Balance(bs(address2.bytes), Some(Amount(amount = 20.dcc)))
    )
  )

  private val assetBalances = TSS(balances =
    Seq(
      TSS.Balance(bs(address1.bytes), Some(Amount(assetId1, 10_000))),
      TSS.Balance(bs(address2.bytes), Some(Amount(assetId2, 20_000)))
    )
  )

  private val dataEntries = TSS(accountData =
    Seq(
      TSS.AccountData(
        bs(address1.bytes),
        Seq(
          DataEntry("foo", DataEntry.Value.Empty),
          DataEntry("bar", DataEntry.Value.StringValue("StringValue")),
          DataEntry("baz", DataEntry.Value.BinaryValue(bs(address1.bytes)))
        )
      ),
      TSS.AccountData(
        bs(address2.bytes),
        Seq(
          DataEntry("foo", DataEntry.Value.IntValue(1200)),
          DataEntry("bar", DataEntry.Value.BoolValue(true))
        )
      )
    )
  )

  private val accountScript = TSS(accountScripts =
    Some(
      TSS.AccountScript(
        bs(signer101.publicKey.arr),
        testScript,
        250
      )
    )
  )

  private val assetScript = TSS(assetScripts = Some(TSS.AssetScript(assetId2, testScript)))

  private val newLease = TSS(
    leaseBalances = Seq(
      TSS.LeaseBalance(bs(address1.bytes), out = 45.dcc),
      TSS.LeaseBalance(bs(address2.bytes), in = 55.dcc)
    ),
    newLeases = Seq(
      TSS.NewLease(leaseId, bs(signer101.publicKey.arr), bs(address2.bytes), 25.dcc)
    )
  )

  private val cancelledLease = TSS(
    leaseBalances = Seq(TSS.LeaseBalance(bs(address3.bytes), out = 20.dcc), TSS.LeaseBalance(bs(TxHelpers.address(104).bytes), in = 0.dcc)),
    cancelledLeases = Seq(
      TSS.CancelledLease(leaseId)
    )
  )

  private val sponsorship = TSS(
    sponsorships = Seq(TSS.Sponsorship(assetId2, 5500))
  )

  private val alias = TSS(
    aliases = Some(TSS.Alias(bs(address2.bytes), "dccevo"))
  )

  private val volumeAndFee = TSS(
    orderFills = Seq(
      TSS.OrderFill(orderId1, 10.dcc, 2000),
      TSS.OrderFill(orderId2, 10.dcc, 2000)
    )
  )

  private val newAsset = TSS(
    assetStatics = Seq(
      TSS.NewAsset(assetId1, hashInt(0x88aadd55), nft = true),
      TSS.NewAsset(assetId2, hashInt(0x88aadd55), decimals = 8)
    ),
    assetVolumes = Seq(
      TSS.AssetVolume(assetId2, true, bs((BigInt(Long.MaxValue) * 10).toByteArray)),
      TSS.AssetVolume(assetId1, false, bs(BigInt(1).toByteArray))
    ),
    assetNamesAndDescriptions = Seq()
  )

  private val reissuedAsset = TSS(
    assetVolumes = Seq(
      TSS.AssetVolume(hashInt(0x23aadd55), false, bs((BigInt(10000000_00L)).toByteArray))
    )
  )
  private val renamedAsset = TSS(
    assetNamesAndDescriptions = Seq(
      TSS.AssetNameAndDescription(
        assetId2,
        "newname",
        "some fancy description"
      )
    )
  )
  private val failedTransaction = TSS(
    balances = Seq(
      TSS.Balance(bs(address2.bytes), Some(Amount(amount = 25.995.dcc)))
    ),
    transactionStatus = TransactionStatus.FAILED
  )
  private val elidedTransaction = TSS(
    transactionStatus = TransactionStatus.ELIDED
  )

  private val withCommitment = TSS(
    generationCommitment = Some(
      TSS.GenerationCommitment(
        bs(signer101.publicKey.arr),
        bs(BlsKeyPair(signer101.privateKey).publicKey.byteStr.arr)
      )
    )
  )

  private val all = TSS(
    assetBalances.balances ++ dccBalances.balances,
    newLease.leaseBalances ++ cancelledLease.leaseBalances,
    newLease.newLeases,
    cancelledLease.cancelledLeases,
    newAsset.assetStatics,
    newAsset.assetVolumes ++ reissuedAsset.assetVolumes,
    newAsset.assetNamesAndDescriptions ++ renamedAsset.assetNamesAndDescriptions,
    newAsset.assetScripts,
    alias.aliases,
    volumeAndFee.orderFills,
    accountScript.accountScripts,
    dataEntries.accountData,
    sponsorship.sponsorships,
    failedTransaction.transactionStatus,
    withCommitment.generationCommitment
  )

  private val testData = Table(
    ("clue", "state snapshot", "base64 bytes", "tx id", "previous state hash", "expected result"),
    (
      "dcc balances",
      dccBalances,
      "CiQKGgE/YP1Q7yDeRXEgffuciL58HC+KIsfiO6liEgYQgJTr3AMKJAoaAT9Cxcljc/UP2BNQYE8cFPKmySVq2tQdw/8SBhCAqNa5Bw==",
      ByteStr.empty,
      Hex.toHexString(TxStateSnapshotHashBuilder.InitStateHash.arr),
      "c853259dbc792e9e6006432744c6ed95c27d3f64936e7235c63b276351a63538"
    ),
    (
      "asset balances",
      assetBalances,
      "CkMKGgE/YP1Q7yDeRXEgffuciL58HC+KIsfiO6liEiUKIF5mn4IKZ9CIbYdHjPBDoqx4XMevVdwxzhB1OUvTUKJbEJBOCkQKGgE/QsXJY3P1D9gTUGBPHBTypsklatrUHcP/EiYKIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOEKCcAQ==",
      ByteStr.empty,
      "c853259dbc792e9e6006432744c6ed95c27d3f64936e7235c63b276351a63538",
      "1a9f931b184922a5050196c29d0e07e61882c01caa252d9f5807a39b1b4d67bb"
    ),
    (
      "data entries",
      dataEntries,
      "YloKGgE/YP1Q7yDeRXEgffuciL58HC+KIsfiO6liEgUKA2ZvbxISCgNiYXJqC1N0cmluZ1ZhbHVlEiEKA2JhemIaAT9g/VDvIN5FcSB9+5yIvnwcL4oix+I7qWJiLwoaAT9Cxcljc/UP2BNQYE8cFPKmySVq2tQdw/8SCAoDZm9vULAJEgcKA2JhclgB",
      ByteStr.empty,
      "1a9f931b184922a5050196c29d0e07e61882c01caa252d9f5807a39b1b4d67bb",
      "c2f2021567946781155cbb13cee9bb02f93e20200f7d4a3ffe6d9fdffb506216"
    ),
    (
      "account script",
      accountScript,
      "Wi4KIFDHWa9Cd6VU8M20LLFHzbBTveERf1sEOw19SUS40GBoEgcGAQaw0U/PGPoB",
      ByteStr.empty,
      "c2f2021567946781155cbb13cee9bb02f93e20200f7d4a3ffe6d9fdffb506216",
      "f63cf7cf61a522efbf465c762f839d87ab2247dc53e5192511a147fef10ffcf7"
    ),
    (
      "asset script",
      assetScript,
      "QisKIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOEgcGAQaw0U/P",
      ByteStr.empty,
      "f63cf7cf61a522efbf465c762f839d87ab2247dc53e5192511a147fef10ffcf7",
      "278cb87062504e53008c74b7fdfb23de56c1012c8918f87f131429c4e668bb18"
    ),
    (
      "new lease",
      newLease,
      "EiIKGgE/YP1Q7yDeRXEgffuciL58HC+KIsfiO6liGICa4uEQEiIKGgE/QsXJY3P1D9gTUGBPHBTypsklatrUHcP/EICuzb4UGmYKILiCMyyFggW8Zd2LGt/AtMr7WWp+kfWbzlN93pXZqzqNEiBQx1mvQnelVPDNtCyxR82wU73hEX9bBDsNfUlEuNBgaBoaAT9Cxcljc/UP2BNQYE8cFPKmySVq2tQdw/8ggPKLqAk=",
      ByteStr.empty,
      "278cb87062504e53008c74b7fdfb23de56c1012c8918f87f131429c4e668bb18",
      "36bca35a1a9eb275aaf9864a2b58b9e2ba41e553065c7102ab47ba37b7ada799"
    ),
    (
      "cancelled lease",
      cancelledLease,
      "EiIKGgE/MCPLqLW81X2Atgaj2KwF9QkaJq6fMkwsGICo1rkHEhwKGgE/YSJd8vzI9rq7GdIuDy65JMc8zi7sC+xrIiIKILiCMyyFggW8Zd2LGt/AtMr7WWp+kfWbzlN93pXZqzqN",
      ByteStr.empty,
      "36bca35a1a9eb275aaf9864a2b58b9e2ba41e553065c7102ab47ba37b7ada799",
      "807cf805c8efda28203c89bd37aef45cde80a2fef73f1bc1cb2a3f521ab441de"
    ),
    (
      "sponsorship",
      sponsorship,
      "aiUKIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOEPwq",
      ByteStr.empty,
      "807cf805c8efda28203c89bd37aef45cde80a2fef73f1bc1cb2a3f521ab441de",
      "0a118e7d96fd0dab0b7d83775a1c63319c8cce5c00557178e9b085f7e3979b5d"
    ),
    (
      "alias",
      alias,
      "SiQKGgE/QsXJY3P1D9gTUGBPHBTypsklatrUHcP/EgZkY2Nldm8=",
      ByteStr.empty,
      "0a118e7d96fd0dab0b7d83775a1c63319c8cce5c00557178e9b085f7e3979b5d",
      "488ecb9bebd49b9a3248f0ffe88e6704bba95b96842ef6b50b35a1be285c1fa7"
    ),
    (
      "order fill",
      volumeAndFee,
      "UisKIMkknO8yHpMUT/XKkkdlrbYCG0Dt+qvVgphfgtRbyRDMEICU69wDGNAPUisKIJZ9YwvJObbWItHAD2zhbaFOTFx2zQ4p0Xbo81GXHKeEEICU69wDGNAP",
      ByteStr.empty,
      "488ecb9bebd49b9a3248f0ffe88e6704bba95b96842ef6b50b35a1be285c1fa7",
      "606040cfd0ed302569278b5075e229f9f2c434ec64736da98c2ff78ae0268e9f"
    ),
    (
      "new asset",
      newAsset,
      "KkYKIF5mn4IKZ9CIbYdHjPBDoqx4XMevVdwxzhB1OUvTUKJbEiDcYGFqY9MotHTpDpskoycN/Mt62bZfPxIC4fpU0ZTBniABKkYKIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOEiDcYGFqY9MotHTpDpskoycN/Mt62bZfPxIC4fpU0ZTBnhgIMi8KIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOEAEaCQT/////////9jIlCiBeZp+CCmfQiG2HR4zwQ6KseFzHr1XcMc4QdTlL01CiWxoBAQ==",
      ByteStr.empty,
      "606040cfd0ed302569278b5075e229f9f2c434ec64736da98c2ff78ae0268e9f",
      "e0075ecbb87aeca1eea7256aae3b894ed795aad80d2ecfc9d525804167cae964"
    ),
    (
      "reissued asset",
      reissuedAsset,
      "MigKIDhvjT3TTlJ+v4Ni205vcYc1m9WWgnQPFovjmJI1H62yGgQ7msoA",
      ByteStr.empty,
      "e0075ecbb87aeca1eea7256aae3b894ed795aad80d2ecfc9d525804167cae964",
      "9807f42797b35db6a1c21d51be43961fe55cf912a368dc74a2ebe2549422d0ef"
    ),
    (
      "renamed asset",
      renamedAsset,
      "OkMKIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOEgduZXduYW1lGhZzb21lIGZhbmN5IGRlc2NyaXB0aW9u",
      ByteStr.empty,
      "9807f42797b35db6a1c21d51be43961fe55cf912a368dc74a2ebe2549422d0ef",
      "ee972aa9b1a869fada95bf13559f263f04d2072c1a59166742e4a864ed5f4720"
    ),
    (
      "failed transaction",
      failedTransaction,
      "CiQKGgE/QsXJY3P1D9gTUGBPHBTypsklatrUHcP/EgYQ4PHE1wlwAQ==",
      ByteStr(fastHash(Ints.toByteArray(0xaabbef20))),
      "ee972aa9b1a869fada95bf13559f263f04d2072c1a59166742e4a864ed5f4720",
      "d9f27e87a1424536610025ae664804c62d80aa925540c9eb42aeded06b45305d"
    ),
    (
      "elided transaction",
      elidedTransaction,
      "cAI=",
      ByteStr(fastHash(Ints.toByteArray(0xaabbef40))),
      "d9f27e87a1424536610025ae664804c62d80aa925540c9eb42aeded06b45305d",
      "58d597e570a9a06555d60f86cbecee11399829d0da879d4070ae9d1a336a56e4"
    ),
    (
      "with generation commitment",
      withCommitment,
      "elQKIFDHWa9Cd6VU8M20LLFHzbBTveERf1sEOw19SUS40GBoEjCtMabxDUdbtJ7shen9xp6fdysl8gapeJylu5iJR4Jzxq24ikwORqZLNx+7yclBPNc=",
      ByteStr.empty,
      "58d597e570a9a06555d60f86cbecee11399829d0da879d4070ae9d1a336a56e4",
      "3a02a5229f7af15a23b673ac130d5b2d51d97d5cee494dc9ea72a16cd47aa136"
    ),
    (
      "all together",
      all,
      "CkMKGgE/YP1Q7yDeRXEgffuciL58HC+KIsfiO6liEiUKIF5mn4IKZ9CIbYdHjPBDoqx4XMevVdwxzhB1OUvTUKJbEJBOCkQKGgE/QsXJY3P1D9gTUGBPHBTypsklatrUHcP/EiYKIHidwBEj1TYPcIKv1LRquL/otRYLv7UmwEPl/Hg6T4lOEKCcAQokChoBP2D9UO8g3kVxIH37nIi+fBwviiLH4jupYhIGEICU69wDCiQKGgE/QsXJY3P1D9gTUGBPHBTypsklatrUHcP/EgYQgKjWuQcSIgoaAT9g/VDvIN5FcSB9+5yIvnwcL4oix+I7qWIYgJri4RASIgoaAT9Cxcljc/UP2BNQYE8cFPKmySVq2tQdw/8QgK7NvhQSIgoaAT8wI8uotbzVfYC2BqPYrAX1CRomrp8yTCwYgKjWuQcSHAoaAT9hIl3y/Mj2ursZ0i4PLrkkxzzOLuwL7GsaZgoguIIzLIWCBbxl3Ysa38C0yvtZan6R9ZvOU33eldmrOo0SIFDHWa9Cd6VU8M20LLFHzbBTveERf1sEOw19SUS40GBoGhoBP0LFyWNz9Q/YE1BgTxwU8qbJJWra1B3D/yCA8ouoCSIiCiC4gjMshYIFvGXdixrfwLTK+1lqfpH1m85Tfd6V2as6jSpGCiBeZp+CCmfQiG2HR4zwQ6KseFzHr1XcMc4QdTlL01CiWxIg3GBhamPTKLR06Q6bJKMnDfzLetm2Xz8SAuH6VNGUwZ4gASpGCiB4ncARI9U2D3CCr9S0ari/6LUWC7+1JsBD5fx4Ok+JThIg3GBhamPTKLR06Q6bJKMnDfzLetm2Xz8SAuH6VNGUwZ4YCDIvCiB4ncARI9U2D3CCr9S0ari/6LUWC7+1JsBD5fx4Ok+JThABGgkE//////////YyJQogXmafggpn0Ihth0eM8EOirHhcx69V3DHOEHU5S9NQolsaAQEyKAogOG+NPdNOUn6/g2LbTm9xhzWb1ZaCdA8Wi+OYkjUfrbIaBDuaygA6QwogeJ3AESPVNg9wgq/UtGq4v+i1Fgu/tSbAQ+X8eDpPiU4SB25ld25hbWUaFnNvbWUgZmFuY3kgZGVzY3JpcHRpb25KJAoaAT9Cxcljc/UP2BNQYE8cFPKmySVq2tQdw/8SBmRjY2V2b1IrCiDJJJzvMh6TFE/1ypJHZa22AhtA7fqr1YKYX4LUW8kQzBCAlOvcAxjQD1IrCiCWfWMLyTm21iLRwA9s4W2hTkxcds0OKdF26PNRlxynhBCAlOvcAxjQD1ouCiBQx1mvQnelVPDNtCyxR82wU73hEX9bBDsNfUlEuNBgaBIHBgEGsNFPzxj6AWJaChoBP2D9UO8g3kVxIH37nIi+fBwviiLH4jupYhIFCgNmb28SEgoDYmFyagtTdHJpbmdWYWx1ZRIhCgNiYXpiGgE/YP1Q7yDeRXEgffuciL58HC+KIsfiO6liYi8KGgE/QsXJY3P1D9gTUGBPHBTypsklatrUHcP/EggKA2Zvb1CwCRIHCgNiYXJYAWolCiB4ncARI9U2D3CCr9S0ari/6LUWC7+1JsBD5fx4Ok+JThD8KnABelQKIFDHWa9Cd6VU8M20LLFHzbBTveERf1sEOw19SUS40GBoEjCtMabxDUdbtJ7shen9xp6fdysl8gapeJylu5iJR4Jzxq24ikwORqZLNx+7yclBPNc=",
      ByteStr(fastHash(Ints.toByteArray(0xaabbef50))),
      "3a02a5229f7af15a23b673ac130d5b2d51d97d5cee494dc9ea72a16cd47aa136",
      "2548e629cb3e3bcd53321619cf6f880de1c3b98fabebfe049a3134ac1cea7714"
    )
  )

  property("correctly create transaction state snapshot hash from snapshot") {
    forAll(testData) { case (clue, pbSnapshot, b64str, txId, prev, expectedResult) =>
      withClue(clue) {
        TSS.parseFrom(Base64.decode(b64str)) shouldEqual pbSnapshot

        val (snapshot, meta) = PBSnapshots.fromProtobuf(pbSnapshot, txId, Height(10))
        val raw = Hex.toHexString(
          TxStateSnapshotHashBuilder
            .createHashFromSnapshot(snapshot, Some(TxStateSnapshotHashBuilder.TxStatusInfo(txId, meta)))
            .createHash(ByteStr(Hex.decodeStrict(prev)))
            .arr
        )
        PBSnapshots.toProtobuf(snapshot, meta) shouldEqual pbSnapshot
        raw shouldEqual expectedResult
      }
    }
  }
}
