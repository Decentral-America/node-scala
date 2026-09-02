package com.decentralchain.state

import cats.syntax.either.*
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{Block, FinalizationVoting}
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.network.{EndorseBlock, MessageCodec, PeerDatabase}
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.test.{FreeSpec, NumericExt, WithResourceManager}
import com.decentralchain.transaction.{CommitToGenerationTransaction, TxHelpers}
import com.decentralchain.utils.EmbeddedChannelOps
import com.decentralchain.wallet.Wallet
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import org.scalactic.source.Position

class BlockEndorserSpec extends FreeSpec, WithDomain, WithResourceManager, EmbeddedChannelOps {
  private val defaultSettings = DomainPresets.DeterministicFinality
    .copy(synchronizationSettings = DomainPresets.DeterministicFinality.synchronizationSettings.copy(maxRollback = 2))
    .configure(
      _.copy(
        generationPeriodLength = 2,
        lightNodeBlockFieldsAbsenceInterval = 0
      )
    )

  "vote" - {
    "starts voting with increased height" in withManager { manager =>
      val generator1 = TxHelpers.signer(0)
      val generator2 = TxHelpers.signer(1)
      val generators = Seq(generator1, generator2)

      var actualFilter = Option.empty[EndorsementFilter]
      withDomain(defaultSettings, AddrWithBalance.enoughBalances(generator1, generator2)) { d =>
        val endorsementStorage = new EndorsementStorage {
          override def tryAdd(msg: EndorseBlock): Either[String, Boolean] = false.asRight
          override def startVoting(filter: EndorsementFilter): Boolean    = {
            actualFilter = Some(filter)
            true
          }
          override def tryCollectAndClear(endorsedId: BlockId): Option[FinalizationVoting] = None
        }

        val channels = manager(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
        val endorser =
          new BlockEndorser.InMemory(d.settings.synchronizationSettings.maxRollback, d.blockchain, d.wallet, endorsementStorage, endorsementStorage, channels)

        log.debug("Append block 2 with commitments")
        val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
        val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = generator1, strictTime = true)
        d.appender.appendBlock(block2WithCommitments)

        log.debug("Append blocks 3 and 4 of new period")
        (3 to 4).foreach { _ =>
          val block = d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = generator1, strictTime = true)
          d.appender.appendBlock(block)
        }

        endorser.vote(d.blockchain.currentGeneratorSet.getOrElse(Seq.empty))
        actualFilter.value.finalizedHeight shouldBe Height(2) // 4 - maxRollback
      }
    }

    "don't broadcast" - {
      "if not enough generating balance" in withManager { manager =>
        val generator1         = Wallet.generateNewAccount(Domain.DefaultWalletSeed, nonce = 0)
        val generator2         = Wallet.generateNewAccount(Domain.DefaultWalletSeed, nonce = 1)
        val otherNodeGenerator = TxHelpers.signer(0)
        val generators         = Seq(generator1, generator2, otherNodeGenerator)
        val generator2Index    = 1

        withDomain(defaultSettings, AddrWithBalance.enoughBalances(generators*)) { d =>
          d.wallet.generateNewAccounts(2)

          val endorsementStorage = new EndorsementStorage {
            override def tryAdd(msg: EndorseBlock): Either[String, Boolean]                  = true.asRight
            override def startVoting(filter: EndorsementFilter): Boolean                     = true
            override def tryCollectAndClear(endorsedId: BlockId): Option[FinalizationVoting] = None
          }

          val channels = manager(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
          val channel1 = manager(new EmbeddedChannel(new MessageCodec(PeerDatabase.NoOp)))
          channels.add(channel1)
          val endorser =
            new BlockEndorser.InMemory(d.settings.synchronizationSettings.maxRollback, d.blockchain, d.wallet, endorsementStorage, endorsementStorage, channels)

          log.debug("Append block 2 with commitments")
          val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
          val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = generator1, strictTime = true)
          d.appender.appendBlock(block2WithCommitments)

          log.debug("Append block 3 of new period with spending all DCC by generator2")
          d.appender.appendBlock(
            d.createBlock(
              version = Block.ProtoBlockVersion,
              txs = Seq(
                TxHelpers.transfer(
                  from = generator2,
                  to = generator1.toAddress,
                  amount = d.blockchain.balance(generator2.toAddress) - CommitToGenerationTransaction.DepositInDcclets - 1.dcc,
                  fee = 1.dcc
                )
              ),
              generator = generator1,
              strictTime = true
            )
          )

          log.debug("Append block 4")
          d.appender.appendBlock(d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = otherNodeGenerator, strictTime = true))

          endorser.vote(d.blockchain.currentGeneratorSet.getOrElse(Nil))
          val xs = channel1.sentEndorsements
          xs should not be empty
          withClue("generator2 didn't endorse: ") {
            xs.count(_.endorserIndex == generator2Index) shouldBe 0
          }
        }
      }
    }
  }

  /** Regression test for the off-by-one era bug: `voteSelf`'s endorsement targets the just-appended
    * block ITSELF (`endorsedHeight == votingHeight`), but it is carried on-chain in the NEXT key block
    * (`votingHeight + 1`), not in a microblock extending the current tip. So when BlsCryptoV2 activates
    * exactly at `votingHeight + 1`, `voteSelf` must sign v2 despite `blockchain.height` (== votingHeight)
    * still being pre-activation -- signing under `votingHeight`'s (legacy) era would produce an
    * endorsement that fails `validateFinalizationVoting`'s `verifyAgg` once embedded in the v2-era
    * carrying block, rejecting the whole block. `vote`'s parent-round doesn't have this problem: its
    * carrier IS the tip at votingHeight, so carrier and voting era always coincide.
    */
  "carrierHeight (era derivation for the block that will actually carry the vote)" - {
    "voteSelf signs under the NEXT block's era, not the current tip's" in withManager { manager =>
      val generator1 = TxHelpers.signer(0)
      val generator2 = TxHelpers.signer(1)
      val generators = Seq(generator1, generator2)

      // Activate BlsCryptoV2 at height 5: block 4 (the tip when voteSelf() is called below) is still
      // pre-activation, but block 5 -- the NEXT key block, which is what actually carries voteSelf's
      // endorsement -- is post-activation.
      val settings = defaultSettings
        .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
        .setFeaturesHeight(BlockchainFeatures.BlsCryptoV2 -> 5)

      var actualFilter = Option.empty[EndorsementFilter]
      withDomain(settings, AddrWithBalance.enoughBalances(generator1, generator2)) { d =>
        val endorsementStorage = new EndorsementStorage {
          override def tryAdd(msg: EndorseBlock): Either[String, Boolean] = false.asRight
          override def startVoting(filter: EndorsementFilter): Boolean = {
            actualFilter = Some(filter)
            true
          }
          override def tryCollectAndClear(endorsedId: BlockId): Option[FinalizationVoting] = None
        }

        val channels = manager(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
        val endorser =
          new BlockEndorser.InMemory(d.settings.synchronizationSettings.maxRollback, d.blockchain, d.wallet, endorsementStorage, endorsementStorage, channels)

        log.debug("Append block 2 with commitments")
        val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
        val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = generator1, strictTime = true)
        d.appender.appendBlock(block2WithCommitments)

        log.debug("Append blocks 3 and 4 of new period -- tip stays pre-activation (activation at 5)")
        (3 to 4).foreach { _ =>
          val block = d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = generator1, strictTime = true)
          d.appender.appendBlock(block)
        }
        d.blockchain.height shouldBe 4
        d.blockchain.supportsBlsCryptoV2(d.blockchain.height) shouldBe false // tip itself is still legacy-era

        endorser.voteSelf(d.blockchain.currentGeneratorSet.getOrElse(Nil))
        withClue("voteSelf must sign under the CARRYING block's (5) era, not the tip's (4) era: ") {
          actualFilter.value.cryptoV2 shouldBe true
        }
      }
    }

    "vote (parent-round) signs under the tip's own era, unlike voteSelf -- pinned as existing behavior" in withManager { manager =>
      val generator1 = TxHelpers.signer(0)
      val generator2 = TxHelpers.signer(1)
      val generators = Seq(generator1, generator2)

      // Same activation height (5) as the voteSelf case above, but vote()'s carrier IS the tip
      // (votingHeight), so at tip height 4 it must still sign legacy -- the opposite of voteSelf.
      val settings = defaultSettings
        .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
        .setFeaturesHeight(BlockchainFeatures.BlsCryptoV2 -> 5)

      var actualFilter = Option.empty[EndorsementFilter]
      withDomain(settings, AddrWithBalance.enoughBalances(generator1, generator2)) { d =>
        val endorsementStorage = new EndorsementStorage {
          override def tryAdd(msg: EndorseBlock): Either[String, Boolean] = false.asRight
          override def startVoting(filter: EndorsementFilter): Boolean = {
            actualFilter = Some(filter)
            true
          }
          override def tryCollectAndClear(endorsedId: BlockId): Option[FinalizationVoting] = None
        }

        val channels = manager(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
        val endorser =
          new BlockEndorser.InMemory(d.settings.synchronizationSettings.maxRollback, d.blockchain, d.wallet, endorsementStorage, endorsementStorage, channels)

        log.debug("Append block 2 with commitments")
        val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
        val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = generator1, strictTime = true)
        d.appender.appendBlock(block2WithCommitments)

        log.debug("Append blocks 3 and 4 of new period -- tip stays pre-activation (activation at 5)")
        (3 to 4).foreach { _ =>
          val block = d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = generator1, strictTime = true)
          d.appender.appendBlock(block)
        }
        d.blockchain.height shouldBe 4

        endorser.vote(d.blockchain.currentGeneratorSet.getOrElse(Nil))
        withClue("vote's carrier IS the tip itself, so it must stay legacy pre-activation: ") {
          actualFilter.value.cryptoV2 shouldBe false
        }
      }
    }
  }
}
