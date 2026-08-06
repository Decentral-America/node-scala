package com.decentralchain.state.diffs

import com.decentralchain.TestValues
import com.decentralchain.account.KeyPair
import com.decentralchain.block.{Block, BlockSnapshot}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto
import com.decentralchain.crypto.DigestLength
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.lagonaki.mocks.TestBlock.BlockWithSigner
import com.decentralchain.mining.MiningConstraint
import com.decentralchain.settings.FunctionalitySettings
import com.decentralchain.state.diffs.BlockDiffer.Result
import com.decentralchain.state.{Blockchain, SnapshotBlockchain, StateSnapshot, TxStateSnapshotHashBuilder}
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.{RideV4, TransactionStateSnapshot, DCCSettingsOps}
import com.decentralchain.test.node.*
import com.decentralchain.transaction.TxValidationError.InvalidStateHash
import com.decentralchain.transaction.{TxHelpers, TxVersion}

class BlockDifferTest extends FreeSpec with WithDomain {
  private val TransactionFee = 10

  private val signerA, signerB = randomKeyPair()

  private val testChain: Seq[BlockWithSigner] = {
    val master, recipient = randomKeyPair()
    getTwoMinersBlockChain(master, recipient, 9)
  }

  "BlockDiffer" - {
    "enableMicroblocksAfterHeight" - {
      /*
      | N | fee | signer | A receive | A balance | B receive | B balance |
      |--:|:---:|:------:|----------:|----------:|----------:|-----------|
      |1  |0    |A       |0          |0          |0          |0          | <- genesis
      |2  |10   |B       |0          |0          |10         |+10        |
      |3  |10   |A       |10         |+10        |0          |0          |
      |4  |10   |B       |0          |10         |+10        |10+10=20   |
      |5  |10   |A       |10         |10+10=20   |0          |20         |
      |6  |10   |B       |0          |20         |+10        |20+10=30   |
      |7  |10   |A       |10         |20+10=30   |0          |30         |
      |8  |10   |B       |0          |30         |+10        |30+10=40   |
      |9  |10   |A       |10         |30+10=40   |0          |40         | <- 1st check
      |10 |10   |B       |0          |40         |+10        |40+10=50   | <- 2nd check
       */
      "height < enableMicroblocksAfterHeight - a miner should receive 100% of the current block's fee" in {
        assertDiff(testChain.init, 1000) { case (_, s) =>
          s.balance(signerA.toAddress) shouldBe 40
        }

        assertDiff(testChain, 1000) { case (_, s) =>
          s.balance(signerB.toAddress) shouldBe 50
        }
      }

      /*
      | N | fee | signer | A receive | A balance | B receive | B balance |
      |--:|:---:|:------:|----------:|----------:|----------:|-----------|
      |1  |0    |A       |0          |0          |0          |0          | <- genesis
      |2  |10   |B       |0          |0          |10         |+10        |
      |3  |10   |A       |10         |+10        |0          |0          |
      |4  |10   |B       |0          |10         |+10        |10+10=20   |
      |5  |10   |A       |10         |10+10=20   |0          |20         |
      |6  |10   |B       |0          |20         |+10        |20+10=30   |
      |7  |10   |A       |10         |20+10=30   |0          |30         |
      |8  |10   |B       |0          |30         |+10        |30+10=40   |
      |9  |10   |A       |10         |30+10=40   |0          |40         |
      |-------------------------- Enable NG -----------------------------|
      |10 |10   |B       |0          |40         |+4         |40+4=44    | <- check
       */
      "height = enableMicroblocksAfterHeight - a miner should receive 40% of the current block's fee only" in {
        assertDiff(testChain, 9) { case (_, s) =>
          s.balance(signerB.toAddress) shouldBe 44
        }
      }

      /*
      | N | fee | signer | A receive | A balance | B receive | B balance |
      |--:|:---:|:------:|----------:|----------:|----------:|-----------|
      |1  |0    |A       |0          |0          |0          |0          | <- genesis
      |2  |10   |B       |0          |0          |10         |+10        |
      |3  |10   |A       |10         |+10        |0          |0          |
      |4  |10   |B       |0          |10         |+10        |10+10=20   |
      |5  |10   |A       |10         |10+10=20   |0          |20         |
      |6  |10   |B       |0          |20         |+10        |20+10=30   |
      |7  |10   |A       |10         |20+10=30   |0          |30         |
      |8  |10   |B       |0          |30         |+10        |30+10=40   |
      |9  |10   |A       |10         |30+10=40   |0          |40         | <- 1st check
      |10 |10   |B       |0          |40         |+10        |40+10=50   | <- 2nd check
       */
      "height > enableMicroblocksAfterHeight - a miner should receive 60% of previous block's fee and 40% of the current one" in {
        assertDiff(testChain.init, 4) { case (_, s) =>
          s.balance(signerA.toAddress) shouldBe 34
        }

        assertDiff(testChain, 4) { case (_, s) =>
          s.balance(signerB.toAddress) shouldBe 50
        }
      }
    }

    "correctly computes state hash" - {
      "genesis block" in {
        val txs = (1 to 10).map(idx => TxHelpers.genesis(TxHelpers.address(idx), 100.dcc)) ++
          (1 to 5).map(idx => TxHelpers.genesis(TxHelpers.address(idx), 1.dcc))
        withDomain(TransactionStateSnapshot.configure(_.copy(lightNodeBlockFieldsAbsenceInterval = 0))) { d =>
          val block = createGenesisWithStateHash(txs, fillStateHash = true)

          block.header.stateHash shouldBe defined
          BlockDiffer
            .fromBlock(d.blockchain, None, block, None, MiningConstraint.Unlimited, block.header.generationSignature) should beRight
        }

        withDomain(DomainPresets.RideV6) { d =>
          val block = createGenesisWithStateHash(txs, fillStateHash = false)

          block.header.stateHash shouldBe None
          BlockDiffer
            .fromBlock(d.blockchain, None, block, None, MiningConstraint.Unlimited, block.header.generationSignature) should beRight
        }
      }

      "arbitrary block/microblock" in
        withDomain(TransactionStateSnapshot.configure(_.copy(lightNodeBlockFieldsAbsenceInterval = 0))) { d =>
          val genesis = createGenesisWithStateHash(Seq(TxHelpers.genesis(TxHelpers.address(1))), fillStateHash = true)
          d.appendBlock(genesis)

          val txs = (1 to 10).map(idx => TxHelpers.transfer(TxHelpers.signer(idx), TxHelpers.address(idx + 1), (100 - idx).dcc))

          val blockTs      = txs.map(_.timestamp).max
          val signer       = TxHelpers.signer(2)
          val blockchain   = SnapshotBlockchain(d.blockchain, Some(d.settings.blockchainSettings.rewardsSettings.initial))
          val initSnapshot = BlockDiffer
            .createInitialBlockSnapshot(d.blockchain, d.lastBlock.id(), signer.toAddress)
            .explicitGet()
          val initStateHash  = TxStateSnapshotHashBuilder.createHashFromSnapshot(initSnapshot, None).createHash(genesis.header.stateHash.get)
          val blockStateHash = TxStateSnapshotHashBuilder
            .computeStateHash(
              txs,
              initStateHash,
              initSnapshot,
              signer,
              d.blockchain.lastBlockTimestamp,
              blockTs,
              isChallenging = false,
              blockchain
            )
            .resultE
            .explicitGet()

          val correctBlock =
            TestBlock.create(blockTs, genesis.id(), txs, signer, version = Block.ProtoBlockVersion, stateHash = Some(blockStateHash))
          BlockDiffer
            .fromBlock(
              blockchain,
              Some(genesis),
              correctBlock.block,
              None,
              MiningConstraint.Unlimited,
              correctBlock.block.header.generationSignature
            ) should beRight

          val incorrectBlock =
            TestBlock
              .create(blockTs, genesis.id(), txs, signer, version = Block.ProtoBlockVersion, stateHash = Some(ByteStr.fill(DigestLength)(1)))
              .block
          BlockDiffer.fromBlock(
            blockchain,
            Some(genesis),
            incorrectBlock,
            None,
            MiningConstraint.Unlimited,
            incorrectBlock.header.generationSignature
          ) shouldBe an[Left[InvalidStateHash, Result]]

          // Regression guard for checkCommittedGeneratorsHash (BlockDiffer): a non-boundary-height
          // block must not carry a committedGeneratorsHash at all. Real per-tag design (see
          // docs/mainnet-upgrade-validation.md): None is always accepted (backward-compatible with
          // blocks mined before the feature existed), but a present Some is strictly validated --
          // at a non-boundary height the expected value is always None, so any Some is a mismatch.
          val blockWithSpuriousCommittedGeneratorsHash =
            Block
              .buildAndSign(
                correctBlock.block.header.version,
                correctBlock.block.header.timestamp,
                correctBlock.block.header.reference,
                correctBlock.block.header.baseTarget,
                correctBlock.block.header.generationSignature,
                correctBlock.block.transactionData,
                signer,
                correctBlock.block.header.featureVotes,
                correctBlock.block.header.rewardVote,
                correctBlock.block.header.stateHash,
                correctBlock.block.header.challengedHeader,
                correctBlock.block.header.finalizationVoting,
                committedGeneratorsHash = Some(ByteStr.fill(DigestLength)(9))
              )
              .explicitGet()
          BlockDiffer.fromBlock(
            blockchain,
            Some(genesis),
            blockWithSpuriousCommittedGeneratorsHash,
            None,
            MiningConstraint.Unlimited,
            blockWithSpuriousCommittedGeneratorsHash.header.generationSignature
          ) shouldBe a[Left[?, ?]]

          // Backward-compatible with the same block having committedGeneratorsHash = None: since
          // the correctBlock itself already has committedGeneratorsHash = None, it was already
          // accepted above -- confirming the None-is-always-accepted path is exercised too.

          d.appendKeyBlock(signer = signer)
          val correctMicroblock =
            d.createMicroBlock(
              Some(
                TxStateSnapshotHashBuilder
                  .computeStateHash(
                    txs,
                    genesis.header.stateHash.get,
                    StateSnapshot.empty,
                    signer,
                    d.blockchain.lastBlockTimestamp,
                    blockTs,
                    isChallenging = false,
                    blockchain
                  )
                  .resultE
                  .explicitGet()
              )
            )(
              txs*
            )
          BlockDiffer.fromMicroBlock(
            blockchain,
            blockchain.lastBlockTimestamp,
            genesis.header.stateHash.get,
            correctMicroblock,
            None,
            MiningConstraint.Unlimited
          ) should beRight

          val incorrectMicroblock = d.createMicroBlock(Some(ByteStr.fill(DigestLength)(1)))(txs*)
          BlockDiffer.fromMicroBlock(
            blockchain,
            blockchain.lastBlockTimestamp,
            genesis.header.stateHash.get,
            incorrectMicroblock,
            None,
            MiningConstraint.Unlimited
          ) shouldBe an[Left[InvalidStateHash, Result]]
        }

      // Regression guard: a GenerationPeriod boundary is the LAST height of the period
      // (period.end, from GenerationPeriod.from/.next), anchored to the DeterministicFinality
      // feature's ACTIVATION height -- not simply "height % generationPeriodLength == 0". That
      // naive modulo check only happens to work when activation == 0 (true on testnet, where
      // the feature is pre-activated at genesis); on any chain where DeterministicFinality
      // activates at a non-zero, non-period-aligned height, the modulo check fires at the WRONG
      // heights and never fires at the real boundaries, so committedGeneratorsHash would
      // silently never be computed or validated where it actually needs to be.
      //
      // Setup: generationPeriodLength = 2, DeterministicFinality activates at height 3 (odd,
      // non-zero, deliberately NOT a multiple of the period length). Real period boundaries
      // under GenerationPeriod's actual arithmetic (period 0 is [activation, activation+length],
      // every period after is +length from there) are then 5, 7, 9, ... -- all ODD heights that
      // "height % 2 == 0" would never select, while it WOULD wrongly fire at the even heights
      // (4, 6, 8) below, none of which are real boundaries.
      "committedGeneratorsHash boundary uses the real GenerationPeriod.end, not height % generationPeriodLength" in {
        val activationHeight = 3
        val settings         = TransactionStateSnapshot
          .configure(_.copy(generationPeriodLength = 2))
          .setFeaturesHeight(BlockchainFeatures.DeterministicFinality -> activationHeight)

        // No CommitToGenerationTransactions submitted, so the committee at every period is
        // empty and the expected hash at any real boundary is simply Blake2b256 of nothing.
        val emptyCommitteeHash = ByteStr(crypto.fastHash(Array.emptyByteArray))

        withDomain(settings) { d =>
          // height 3 % 2 == 0 is false, but height 4 % 2 == 0 is true -- exactly the false
          // boundary the old naive check would have picked. Build a *candidate* block for
          // height 4 (one before the real boundary at 5) carrying the hash that would be
          // expected at a real boundary, and confirm BlockDiffer rejects it: at a real
          // non-boundary height the expected value is always None, so any Some is a mismatch.
          // (verify = false: only the committedGeneratorsHash check is under test here, so the
          // block doesn't need a valid signature -- buildAndSign already produces one anyway,
          // just against a hand-picked header field it wasn't originally signed over.)
          while (d.blockchain.height < 3) d.appendBlock()
          val candidateHeight4 = d.createBlock(Block.ProtoBlockVersion, Nil)
          val taggedHeight4    = candidateHeight4.copy(header = candidateHeight4.header.copy(committedGeneratorsHash = Some(emptyCommitteeHash)))
          BlockDiffer.fromBlock(
            d.blockchain,
            Some(d.lastBlock),
            taggedHeight4,
            None,
            MiningConstraint.Unlimited,
            taggedHeight4.header.generationSignature,
            verify = false
          ) shouldBe a[Left[?, ?]]

          // Now actually advance to height 4 for real (untagged, as the real miner would produce
          // it -- committedGeneratorsHash None, always accepted) ...
          d.appendBlock()

          // ... and build a candidate for height 5, the REAL boundary (period [3, 5]). The
          // correct hash must now be accepted.
          val candidateHeight5 = d.createBlock(Block.ProtoBlockVersion, Nil)
          val correctHeight5   = candidateHeight5.copy(header = candidateHeight5.header.copy(committedGeneratorsHash = Some(emptyCommitteeHash)))
          BlockDiffer.fromBlock(
            d.blockchain,
            Some(d.lastBlock),
            correctHeight5,
            None,
            MiningConstraint.Unlimited,
            correctHeight5.header.generationSignature,
            verify = false
          ) should beRight

          // ... while a WRONG hash at that same real boundary must still be rejected.
          val wrongHeight5 =
            candidateHeight5.copy(header = candidateHeight5.header.copy(committedGeneratorsHash = Some(ByteStr.fill(DigestLength)(9))))
          BlockDiffer.fromBlock(
            d.blockchain,
            Some(d.lastBlock),
            wrongHeight5,
            None,
            MiningConstraint.Unlimited,
            wrongHeight5.header.generationSignature,
            verify = false
          ) shouldBe a[Left[?, ?]]
        }
      }

      // Regression guard, found via a live fresh-genesis P2P replay of the real testnet chain
      // diverging at the block immediately after its first-ever CommitToGenerationTransaction
      // commitments (canonical height 1799): a CommitToGenerationTransaction's fee must NOT
      // carry over into the next block's miner reward via the standard NG 60/40 split -- neither
      // in the pre-sponsorship recompute-from-raw-block-data path (feeFromPreviousBlockE above)
      // nor in the post-sponsorship persisted-carryFee path (computeTxFeeInfo below). The
      // commitment fee is consumed entirely by the block that includes it, for the same
      // position-dependent-state reason the commitment DATA is excluded from the state hash
      // (see TxStateSnapshotHashBuilder.scala): letting it carry forward would make the amount
      // credited to the NEXT miner depend on which block position the commitment landed at.
      "CommitToGenerationTransaction fee does not carry over via the NG 60/40 split" - {
        "pre-sponsorship" in {
          // RideV4 gives BlockV5 (feature 15), required for CommitToGenerationTransaction's
          // general tx-validation barrier, plus everything below it -- but FeeSponsorship is
          // pushed far out so this matches the real testnet's pre-sponsorship condition.
          val settings = RideV4
            .addFeatures(BlockchainFeatures.DeterministicFinality)
            .setFeaturesHeight(BlockchainFeatures.FeeSponsorship -> 1000000)
          val committer = TxHelpers.signer(20)
          val fee       = 1000000L

          withDomain(settings, AddrWithBalance.enoughBalances(committer)) { d =>
            (1 to 5).foreach(_ => d.appendBlock())
            val next = d.blockchain.currentGenerationPeriod.get.next.start

            val committerBalanceBefore = d.balance(committer.toAddress)
            d.appendBlock(TxHelpers.commitToGeneration(next, committer, fee = fee))
            val minerBeforeNextBlock = d.balance(d.lastBlock.sender.toAddress)

            d.appendBlock() // empty block; its miner reward must be reward-only, no carry
            d.balance(d.lastBlock.sender.toAddress) - minerBeforeNextBlock shouldBe d.blockchain.settings.rewardsSettings.initial

            // sanity check: the committer really did pay the fee (proves the tx actually landed,
            // rather than this test accidentally not exercising the commit path at all)
            committerBalanceBefore - d.balance(committer.toAddress) shouldBe fee
          }
        }

        "post-sponsorship" in {
          val settings  = RideV4.addFeatures(BlockchainFeatures.DeterministicFinality)
          val committer = TxHelpers.signer(21)
          val fee       = 1000000L

          withDomain(settings, AddrWithBalance.enoughBalances(committer)) { d =>
            (1 to 5).foreach(_ => d.appendBlock())
            val next = d.blockchain.currentGenerationPeriod.get.next.start

            d.appendBlock(TxHelpers.commitToGeneration(next, committer, fee = fee))
            val minerBeforeNextBlock = d.balance(d.lastBlock.sender.toAddress)

            d.appendBlock()
            d.balance(d.lastBlock.sender.toAddress) - minerBeforeNextBlock shouldBe d.blockchain.settings.rewardsSettings.initial
          }
        }
      }
    }

    "result of txs validation should be equal the result of snapshot apply" in {
      val sender = TxHelpers.signer(1)
      withDomain(DomainPresets.TransactionStateSnapshot, AddrWithBalance.enoughBalances(sender)) { d =>
        (1 to 5).map { idx =>
          val (refBlock, refSnapshot, carry, _, refStateHash, _) = d.liquidState.get.snapshotOf(d.lastBlock.id()).get
          val refBlockchain                                      = SnapshotBlockchain(
            d.rocksDBWriter,
            refSnapshot,
            refBlock,
            d.liquidState.get.hitSource,
            carry,
            d.blockchain.computeNextReward,
            Some(refStateHash)
          )

          val block = d.createBlock(Block.ProtoBlockVersion, Seq(TxHelpers.transfer(sender, amount = idx.dcc, fee = TestValues.fee * idx)))
          val hs    = d.posSelector.validateGenerationSignature(block).explicitGet()
          val txValidationResult = BlockDiffer.fromBlock(refBlockchain, Some(refBlock), block, None, MiningConstraint.Unlimited, hs)

          val txInfo        = txValidationResult.explicitGet().snapshot.transactions.head._2
          val blockSnapshot = BlockSnapshot(block.id(), Seq(txInfo.snapshot -> txInfo.status))

          val snapshotApplyResult = BlockDiffer.fromBlock(refBlockchain, Some(refBlock), block, Some(blockSnapshot), MiningConstraint.Unlimited, hs)

          // NOTE: Retained for NODE-2610 compatibility (upstream ticket)
          def clearAffected(r: Result): Result = {
            r.copy(
              snapshot = r.snapshot.copy(transactions = r.snapshot.transactions.map { case (id, info) => id -> info.copy(affected = Set.empty) }),
              keyBlockSnapshot = r.keyBlockSnapshot.copy(transactions = r.keyBlockSnapshot.transactions.map { case (id, info) =>
                id -> info.copy(affected = Set.empty)
              })
            )

          }

          val snapshotApplyResultWithoutAffected = snapshotApplyResult.map(clearAffected)
          val txValidationResultWithoutAffected  = txValidationResult.map(clearAffected)

          snapshotApplyResultWithoutAffected shouldBe txValidationResultWithoutAffected
        }
      }
    }

    "should be possible to append key block that references non-last microblock (NODE-1172)" in {
      val sender   = TxHelpers.signer(1)
      val minerAcc = TxHelpers.signer(2)
      val settings = DomainPresets.TransactionStateSnapshot
      val time     = TestTime() // NOTE: Could use d.testTime — current approach works correctly
      withDomain(
        settings.copy(minerSettings = settings.minerSettings.copy(quorum = 0)),
        AddrWithBalance.enoughBalances(sender, minerAcc),
        time = time
      ) { d =>
        d.appendBlock()
        time.setTime(d.lastBlock.header.timestamp)

        time.advance(d.settings.minerSettings.minMicroBlockAge)
        val refId = d.appendMicroBlock(TxHelpers.transfer(sender, amount = 1))

        time.advance(d.settings.minerSettings.minMicroBlockAge)
        d.appendMicroBlock(TxHelpers.transfer(sender, amount = 2))

        d.appender.appendBlock(
          d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, ref = Some(refId), strictTime = true, generator = minerAcc)
        )
      }
    }
  }

  private def assertDiff(blocks: Seq[BlockWithSigner], ngAtHeight: Int)(assertion: (StateSnapshot, Blockchain) => Unit): Unit = {
    val fs = FunctionalitySettings(
      featureCheckBlocksPeriod = ngAtHeight / 2,
      blocksForFeatureActivation = 1,
      preActivatedFeatures = Map[Short, Int]((2, ngAtHeight)),
      doubleFeaturesPeriodsAfterHeight = Int.MaxValue
    )
    assertNgDiffState(blocks.init, blocks.last, fs)(assertion)
  }

  private def getTwoMinersBlockChain(from: KeyPair, to: KeyPair, numPayments: Int): Seq[BlockWithSigner] = {
    val genesisTx            = TxHelpers.genesis(from.toAddress, Long.MaxValue - 1)
    val features: Seq[Short] = Seq[Short](2)

    val paymentTxs = (1 to numPayments).map { _ =>
      TxHelpers.transfer(from, to.toAddress, 10000, fee = TransactionFee, version = TxVersion.V1)
    }

    (genesisTx +: paymentTxs).zipWithIndex.map { case (x, i) =>
      val signer = if (i % 2 == 0) signerA else signerB
      TestBlock.create(signer, Seq(x), features)
    }
  }
}
