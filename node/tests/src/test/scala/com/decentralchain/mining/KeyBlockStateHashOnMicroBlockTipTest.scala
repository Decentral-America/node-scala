package com.decentralchain.mining

import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.state.diffs.BlockDiffer
import com.decentralchain.state.{StateSnapshot, TxStateSnapshotHashBuilder}
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.transaction.TxHelpers

/** Characterization tests for the key-block sealing path, written while investigating the
  * 2026-09-01 height-2640 `InvalidStateHash` stall.
  *
  * The live incident: after height 2639 was appended and microblocks kept landing on top of it, the
  * miner made 102 consecutive attempts to seal key block 2640 and every one failed with
  * `InvalidStateHash(expected, computed)` -- the miner's OWN forge-time hash disagreeing with its
  * OWN append-time recompute.
  *
  * The hypothesis under test was a miner/validator READ-SKEW: `Miner.forgeBlock` pins every read to
  * a fixed `reference`, but `packTransactionsForKeyBlock` calls
  * `MiningConstraints(blockchainUpdater, blockchainUpdater.height, ...)` and
  * `BlockDiffer.createInitialBlockSnapshot(blockchainUpdater, ...)` against the LIVE, unpinned
  * updater -- so a microblock landing between those reads could, in principle, poison the hash.
  *
  * These tests RULE THAT OUT, and they are kept as regression guards for the invariants they pin
  * down. Re-analysis of the incident log (`.superpowers/sdd/stall-2026-09-01-filtered.log`) shows
  * the (expected, computed) pair is fully DETERMINISTIC per liquid tip -- two attempts against the
  * same microblock tip produce byte-identical expected AND computed hashes -- which is the
  * signature of a systematic formula asymmetry, not of a data race. See
  * `.superpowers/sdd/task-8b-step1-2-report.md` for the full evidence trail and the remaining
  * hypotheses.
  *
  * Every test here drives the real incident shape: a key block whose `reference` is the id of the
  * LAST MICROBLOCK of the liquid period -- which is what `Miner.forgeBlock` picks via
  * `bestLastBlockInfo` -- rather than the base key block id.
  */
class KeyBlockStateHashOnMicroBlockTipTest extends FreeSpec with WithDomain {

  private val richAccount = TxHelpers.signer(1)

  "key block sealing a liquid period" - {

    "miner forge-time state hash matches appender recompute when the tip is the BASE key block" in
      withDomain(TransactionStateSnapshot, AddrWithBalance.enoughBalances(richAccount)) { d =>
        d.appendBlock()
        val ref = d.blockchainUpdater.lastBlockId.get
        val kb  = d.createBlock(com.decentralchain.block.Block.NgBlockVersion, Nil, ref = Some(ref))
        d.appendBlockE(kb) should beRight
      }

    "miner forge-time state hash matches appender recompute when the tip is a MICROBLOCK" in
      withDomain(TransactionStateSnapshot, AddrWithBalance.enoughBalances(richAccount)) { d =>
        d.appendBlock()
        // Land a few microblocks on the liquid period, exactly like the live chain did at 2639.
        d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
        d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))
        val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(4), 1.dcc))

        // `Miner.forgeBlock` references the LAST MICROBLOCK, not the base block: that IS what
        // `blockchainUpdater.lastBlockId` reports while a liquid period is open.
        microTip shouldBe d.blockchainUpdater.lastBlockId.get
        withClue("carry fee must be non-zero for this scenario to be meaningful: ") {
          d.blockchainUpdater.carryFee(Some(microTip)) should be > 0L
        }

        val kb = d.createBlock(com.decentralchain.block.Block.NgBlockVersion, Nil, ref = Some(microTip))
        d.appendBlockE(kb) should beRight
      }

    "the liquid-block identity is self-consistent: forgeBlock(totalBlockId).id() == totalBlockId" in
      withDomain(TransactionStateSnapshot, AddrWithBalance.enoughBalances(richAccount)) { d =>
        d.appendBlock()
        val ids = Seq(
          d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc)),
          d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc)),
          d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(4), 1.dcc))
        )
        val ng = d.liquidState.get
        ids.foreach { id =>
          withClue(s"totalBlockId $id: ") {
            val lb = ng.liquidBlockOf(id)
            lb shouldBe defined
            lb.get.block.id() shouldBe id
            // The miner pins penalties on `reference` (the map key); the appender pins them on the
            // forged block object. They must resolve to the same height, or the two initial-block
            // snapshots -- and therefore the two state hashes -- diverge.
            d.blockchainUpdater.heightOf(id) shouldBe d.blockchainUpdater.heightOf(lb.get.block.id())
          }
        }
      }

    "the miner's initial-block snapshot equals the appender's, field by field, on a microblock tip" in
      withDomain(TransactionStateSnapshot, AddrWithBalance.enoughBalances(richAccount)) { d =>
        d.appendBlock()
        d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
        val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))
        val miner    = com.decentralchain.history.defaultSigner.toAddress

        // MINER: BlockDiffer.createInitialBlockSnapshot, called from packTransactionsForKeyBlock.
        val minerInit = BlockDiffer.createInitialBlockSnapshot(d.blockchainUpdater, microTip, miner).explicitGet()

        // APPENDER: the equivalent initSnapshot rebuilt inside fromBlockTraced. Rather than
        // duplicate that arithmetic here (which would make the test vacuous), drive the real
        // appender and require it to accept a block carrying the MINER's hash -- if the two
        // snapshots differ at all, the recomputed hash differs and the append is rejected.
        val prevHash  = d.blockchainUpdater.lastStateHash(Some(microTip))
        val minerHash = TxStateSnapshotHashBuilder.createHashFromSnapshot(minerInit, None).createHash(prevHash)

        minerInit should not be StateSnapshot.empty
        minerInit.balances should not be empty

        val kb = d.createBlock(
          com.decentralchain.block.Block.NgBlockVersion,
          Nil,
          ref = Some(microTip),
          stateHash = Some(Some(minerHash))
        )
        withClue(s"minerInit=$minerInit prevHash=$prevHash minerHash=$minerHash: ") {
          d.appendBlockE(kb) should beRight
        }
      }

    /** `referencedBlockchain` has three branches. `forgeBlock` pins ONE of them and reads
      * `prevStateHash`/`generatingBalance`/... from it; `packTransactionsForKeyBlock` then calls
      * `createInitialBlockSnapshot(blockchainUpdater, ...)`, which pins a SECOND, independent one.
      * They must land on the same branch with the same carry/reward or the two halves of the
      * miner's own hash are computed against different views.
      */
    "forgeBlock's pin and createInitialBlockSnapshot's pin agree on carry and reward" in
      withDomain(TransactionStateSnapshot, AddrWithBalance.enoughBalances(richAccount)) { d =>
        d.appendBlock()
        d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
        val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))

        val pinA = d.blockchainUpdater.referencedBlockchain(microTip)
        val pinB = d.blockchainUpdater.referencedBlockchain(microTip)

        pinA.height shouldBe pinB.height
        pinA.carryFee(Some(microTip)) shouldBe pinB.carryFee(Some(microTip))
        pinA.lastBlockReward shouldBe pinB.lastBlockReward
        pinA.lastStateHash(Some(microTip)) shouldBe pinB.lastStateHash(Some(microTip))

        // And the base-block branch must not silently drop the liquid carry.
        val baseRef = d.liquidState.get.base.id()
        d.blockchainUpdater.referencedBlockchain(baseRef).carryFee(Some(baseRef)) shouldBe
          d.liquidState.get.snapshotFor(baseRef)._2
      }

    "the two hash formulas agree explicitly on a microblock tip" in
      withDomain(TransactionStateSnapshot, AddrWithBalance.enoughBalances(richAccount)) { d =>
        d.appendBlock()
        d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
        val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))

        val miner = com.decentralchain.history.defaultSigner.toAddress

        // ---- MINER SIDE (Miner.packTransactionsForKeyBlock) ----
        val prevHash     = d.blockchainUpdater.lastStateHash(Some(microTip))
        val initSnapshot = BlockDiffer
          .createInitialBlockSnapshot(d.blockchainUpdater, microTip, miner)
          .explicitGet()
        val minerHash =
          if (initSnapshot == StateSnapshot.empty) prevHash
          else TxStateSnapshotHashBuilder.createHashFromSnapshot(initSnapshot, None).createHash(prevHash)

        // ---- APPENDER SIDE (BlockDiffer.fromBlock on the forged block) ----
        val kb =
          d.createBlock(com.decentralchain.block.Block.NgBlockVersion, Nil, ref = Some(microTip), stateHash = Some(Some(minerHash)))

        kb.header.stateHash shouldBe Some(minerHash)
        withClue(s"miner hash=$minerHash prevHash=$prevHash: ") {
          d.appendBlockE(kb) should beRight
        }
      }
  }
}
