package com.decentralchain.mining

import com.decentralchain.account.Address
import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeature
import com.decentralchain.state.diffs.BlockDiffer
import com.decentralchain.state.diffs.BlockDiffer.CurrentBlockFeePart
import com.decentralchain.state.{Blockchain, Portfolio, Sponsorship, StateSnapshot, TxStateSnapshotHashBuilder}
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.transaction.TxHelpers

/** Task 8b step 4 -- the CARRY-FEE lead for the 2026-09-01 height-2640 `InvalidStateHash` stall.
  *
  * The asymmetry under test:
  *
  *   - MINER (`BlockDiffer.createInitialBlockSnapshot`, BlockDiffer.scala:438-439) reads
  *     `blockchain.carryFee(Some(reference))` UNCONDITIONALLY, where `blockchain` is
  *     `blockchainUpdater.referencedBlockchain(reference)` -- a `SnapshotBlockchain`, whose
  *     `carryFee` ignores its `refId` argument entirely and returns the stored `carry` field.
  *
  *   - APPENDER (`BlockDiffer.fromBlockTraced`, BlockDiffer.scala:130-184) branches THREE ways on
  *     the same pinned blockchain:
  *       (a) `stateHeight >= sponsorshipHeight` -> `blockchain.carryFee(None)`  [== the same `carry`]
  *       (b) `stateHeight >  ngHeight`          -> RECOMPUTE 60% of `pb.transactionData` fees
  *       (c) otherwise                          -> `Portfolio.empty`
  *
  * Branch (a) provably agrees with the miner (`SnapshotBlockchain.carryFee` ignores refId). Branch
  * (b) does NOT read `carry` at all -- it recomputes. And `computeTxFeeInfo`'s `carry` accumulator
  * is gated on `hasSponsorship`, so PRE-sponsorship the stored `carry` is structurally 0 while the
  * recompute is non-zero for any fee-paying previous block.
  *
  * Height correlation with the real incident: on the live relaunch chain
  * (`Ecosystem/infra/node-config/testnet/dcc.conf`) FeeSponsorship (id 7) is pre-activated at
  * height 0 and `feature-check-blocks-period = 3000`, with
  * `double-features-periods-after-height = 0`, so
  * `sponsorshipHeight = 0 + activationWindowSize(0) = 3000 * 1 = 3000`. The stall was at
  * stateHeight 2639 -- BELOW 3000 -- so the appender took branch (b) while the miner read `carry`.
  *
  * These tests establish the real values on both sides rather than arguing about them.
  */
class KeyBlockCarryFeeAsymmetryTest extends FreeSpec with WithDomain {

  private val richAccount        = TxHelpers.signer(1)
  private val minerAddr: Address = com.decentralchain.history.defaultSigner.toAddress

  /** Recomputes the APPENDER's branch-(b) carry from a previous block, verbatim per
    * BlockDiffer.scala:168-176. Kept independent of production code on purpose: if production
    * changes, this must be re-derived deliberately.
    */
  private def appenderBranchBCarry(pb: Block): Long =
    pb.transactionData
      .filterNot(_.isInstanceOf[com.decentralchain.transaction.CommitToGenerationTransaction])
      .map { t =>
        val pf = Portfolio.build(t.assetFee)
        pf.minus(pf.multiply(CurrentBlockFeePart))
      }
      .foldLeft(Portfolio.empty)((a, b) => a.combine(b).explicitGet())
      .balance

  private def branchOf(bc: Blockchain, maybePrevBlockDefined: Boolean): String = {
    val stateHeight       = bc.height
    val ngHeight          = bc.featureActivationHeight(com.decentralchain.features.BlockchainFeatures.NG).map(_.toInt).getOrElse(Int.MaxValue)
    val sponsorshipHeight = Sponsorship.sponsoredFeesSwitchHeight(bc).toInt
    if (stateHeight >= sponsorshipHeight) "a:sponsorship-carryFee(None)"
    else if (stateHeight > ngHeight) if (maybePrevBlockDefined) "b:ng-recompute" else "b:ng-recompute(empty prevBlock)"
    else "c:empty"
  }

  "carry-fee: miner unconditional read vs appender three-way branch" - {

    "FACT: which branch the appender takes under the default test preset, and the real values on both sides" in
      withDomain(TransactionStateSnapshot, AddrWithBalance.enoughBalances(richAccount)) { d =>
        d.appendBlock()
        // A fee-paying microblock: this is what makes branch (b)'s recompute non-zero.
        d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
        val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))

        val pinned    = d.blockchainUpdater.referencedBlockchain(microTip)
        val prevBlock = d.liquidState.get.liquidBlockOf(microTip).get.block

        val sponsorshipHeight = Sponsorship.sponsoredFeesSwitchHeight(pinned).toInt
        val ngHeight          = pinned.featureActivationHeight(com.decentralchain.features.BlockchainFeatures.NG).map(_.toInt).getOrElse(Int.MaxValue)

        val minerCarry        = pinned.carryFee(Some(microTip))
        val appenderRecompute = appenderBranchBCarry(prevBlock)

        val fsx = pinned.settings.functionalitySettings
        info(
          s"featureCheckBlocksPeriod=${fsx.featureCheckBlocksPeriod} doubleFeaturesPeriodsAfterHeight=${fsx.doubleFeaturesPeriodsAfterHeight} " +
            s"activationWindowSize(0)=${fsx.activationWindowSize(0)} " +
            s"featureActivationHeight(FeeSponsorship)=${pinned.featureActivationHeight(com.decentralchain.features.BlockchainFeatures.FeeSponsorship)}"
        )
        info(s"stateHeight=${pinned.height} ngHeight=$ngHeight sponsorshipHeight=$sponsorshipHeight")
        info(s"branch=${branchOf(pinned, maybePrevBlockDefined = true)}")
        info(s"MINER  carryFee(Some(ref)) = $minerCarry")
        info(s"APPEND branch-(b) recompute = $appenderRecompute")
        info(s"prevBlock txs = ${prevBlock.transactionData.size}, total fee = ${prevBlock.transactionData.map(_.assetFee._2).sum}")

        // Non-vacuity: the previous block must actually carry fee-paying transactions, or neither
        // side can differ and the whole comparison is meaningless.
        prevBlock.transactionData should not be empty
        prevBlock.transactionData.map(_.assetFee._2).sum should be > 0L
      }

    "miner's carryFee(Some(ref)) and carryFee(None) are the SAME on a SnapshotBlockchain (refId is ignored)" in
      withDomain(TransactionStateSnapshot, AddrWithBalance.enoughBalances(richAccount)) { d =>
        d.appendBlock()
        d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
        val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))
        val pinned   = d.blockchainUpdater.referencedBlockchain(microTip)
        // If these ever differ, branch (a) and the miner's read diverge too.
        pinned.carryFee(Some(microTip)) shouldBe pinned.carryFee(None)
        pinned.carryFee(Some(ByteStr(Array.fill[Byte](32)(7)))) shouldBe pinned.carryFee(None)
      }

    /** THE REAL-CHAIN CONFIGURATION.
      *
      * Every stock preset routes through `ScriptsAndSponsorship`, which does
      * `setFeaturesHeight(FeeSponsorship -> -activationWindowSize(1))` == `-3000` (DomainPresets.scala:79)
      * -- deliberately, so that `sponsoredFeesSwitchHeight = -3000 + 3000 = 0` and sponsorship is
      * live from height 0 in tests. That is why the entire existing suite only ever exercises the
      * appender's branch (a), and why this asymmetry has never been covered.
      *
      * The LIVE relaunch chain does NOT do that: `pre-activated-features { ... 7 = 0 ... }` gives
      * `featureActivationHeight(FeeSponsorship) = 0`, hence
      * `sponsoredFeesSwitchHeight = 0 + 3000 = 3000`. Below height 3000 -- which includes the
      * stall at 2639 -- the appender takes branch (b).
      *
      * These tests reproduce that exact configuration.
      */
    "REAL-CHAIN CONFIG (FeeSponsorship pre-activated at 0 => sponsorshipHeight=3000)" - {

      // TransactionStateSnapshot, but with the -3000 test hack undone: FeeSponsorship at 0, as the
      // live chain has it. Everything else identical.
      val realChainSettings =
        TransactionStateSnapshot.setFeaturesHeight(com.decentralchain.features.BlockchainFeatures.FeeSponsorship -> 0)

      "the appender really does take branch (b) here, and the two sides' carry values are recorded" in
        withDomain(realChainSettings, AddrWithBalance.enoughBalances(richAccount)) { d =>
          d.appendBlock()
          d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
          val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))

          val pinned    = d.blockchainUpdater.referencedBlockchain(microTip)
          val prevBlock = d.liquidState.get.liquidBlockOf(microTip).get.block

          val sponsorshipHeight = Sponsorship.sponsoredFeesSwitchHeight(pinned).toInt
          val minerCarry        = pinned.carryFee(Some(microTip))
          val appenderRecompute = appenderBranchBCarry(prevBlock)

          info(s"stateHeight=${pinned.height} sponsorshipHeight=$sponsorshipHeight branch=${branchOf(pinned, true)}")
          info(s"MINER  carryFee(Some(ref)) = $minerCarry")
          info(s"APPEND branch-(b) recompute = $appenderRecompute")

          withClue("the whole point of this block is to sit BELOW sponsorshipHeight: ") {
            pinned.height should be < sponsorshipHeight
          }
          withClue("non-vacuity -- the referenced block must carry real fees: ") {
            prevBlock.transactionData.map(_.assetFee._2).sum should be > 0L
          }
        }

      /** The unit-level statement of the fix.
        *
        * The raw `carryFee(Some(ref))` accessor is NOT changed by the fix and still returns the
        * stored (structurally 0, pre-sponsorship) carry -- asserting otherwise would be asserting
        * the wrong invariant. What must hold is that the MINER and the APPENDER call the SAME
        * formula: `BlockDiffer.carryFeeFromPreviousBlock`, the single source of truth both sides now
        * use. This test pins that the shared formula picks the recompute here, and that the raw
        * accessor the miner used to read would have given a different answer -- i.e. the bug was
        * real and the fix is load-bearing, not a no-op.
        */
      "GREEN: the shared carryFeeFromPreviousBlock picks the recompute, NOT the stored carry" in
        withDomain(realChainSettings, AddrWithBalance.enoughBalances(richAccount)) { d =>
          d.appendBlock()
          d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
          val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))

          val pinned    = d.blockchainUpdater.referencedBlockchain(microTip)
          val prevBlock = d.blockchainUpdater.referencedBlock(microTip).get

          val shared    = BlockDiffer.carryFeeFromPreviousBlock(pinned, Some(prevBlock)).explicitGet().balance
          val recompute = appenderBranchBCarry(prevBlock)
          val storedRaw = pinned.carryFee(Some(microTip))

          info(s"shared=$shared recompute=$recompute storedRawAccessor=$storedRaw")

          withClue("the shared formula must agree with the appender's branch-(b) recompute: ") {
            shared shouldBe recompute
          }
          withClue("non-vacuity -- the recompute must actually be non-zero here: ") {
            recompute should be > 0L
          }
          withClue("and it must NOT be the stored carry, or the old miner code would have been fine: ") {
            shared should not be storedRaw
          }
        }

      "GREEN: miner's referencedBlock resolves the same previous block the appender pairs with" in
        withDomain(realChainSettings, AddrWithBalance.enoughBalances(richAccount)) { d =>
          d.appendBlock()
          d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
          val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))

          // liquid-period reference
          d.blockchainUpdater.referencedBlock(microTip).map(_.id()) shouldBe Some(microTip)

          // persisted reference, after the liquid period settles
          d.appendBlock()
          val persisted = d.blockchainUpdater.lastBlockId.get
          d.blockchainUpdater.referencedBlock(persisted).map(_.id()) shouldBe Some(persisted)

          // an unrelated id resolves to nothing (and must not silently fall back to some other block)
          d.blockchainUpdater.referencedBlock(ByteStr(Array.fill[Byte](32)(9))) shouldBe None
        }

      /** The other reachable reference shape: no liquid period open, so `reference` is the LAST
        * PERSISTED block. The appender pairs this with `rocksdb.lastBlock`, so branch (b) still has
        * a real previous block to recompute from -- the miner must resolve the same one.
        */
      "RED END-TO-END (persisted-block reference, no liquid period): miner hash must be accepted" in
        withDomain(realChainSettings, AddrWithBalance.enoughBalances(richAccount)) { d =>
          // A settled block carrying real fees, with NO microblocks on top.
          d.appendBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
          d.appendBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))

          val ref    = d.blockchainUpdater.lastBlockId.get
          val pinned = d.blockchainUpdater.referencedBlockchain(ref)
          withClue("must sit BELOW sponsorshipHeight to exercise branch (b): ") {
            pinned.height should be < Sponsorship.sponsoredFeesSwitchHeight(pinned).toInt
          }

          val prevHash  = d.blockchainUpdater.lastStateHash(Some(ref))
          val minerInit = BlockDiffer.createInitialBlockSnapshot(d.blockchainUpdater, ref, minerAddr).explicitGet()
          val minerHash =
            if (minerInit == StateSnapshot.empty) prevHash
            else TxStateSnapshotHashBuilder.createHashFromSnapshot(minerInit, None).createHash(prevHash)

          val kb = d.createBlock(Block.NgBlockVersion, Nil, ref = Some(ref), stateHash = Some(Some(minerHash)))
          withClue(s"minerHash=$minerHash prevHash=$prevHash: ") {
            d.appendBlockE(kb) should beRight
          }
        }

      "RED END-TO-END: a key block carrying the MINER's forge-time hash must be accepted by the real appender" in
        withDomain(realChainSettings, AddrWithBalance.enoughBalances(richAccount)) { d =>
          d.appendBlock()
          d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
          val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))

          val pinned = d.blockchainUpdater.referencedBlockchain(microTip)
          withClue("must sit BELOW sponsorshipHeight to exercise branch (b): ") {
            pinned.height should be < Sponsorship.sponsoredFeesSwitchHeight(pinned).toInt
          }

          val prevHash  = d.blockchainUpdater.lastStateHash(Some(microTip))
          val minerInit = BlockDiffer.createInitialBlockSnapshot(d.blockchainUpdater, microTip, minerAddr).explicitGet()
          val minerHash =
            if (minerInit == StateSnapshot.empty) prevHash
            else TxStateSnapshotHashBuilder.createHashFromSnapshot(minerInit, None).createHash(prevHash)

          val kb = d.createBlock(Block.NgBlockVersion, Nil, ref = Some(microTip), stateHash = Some(Some(minerHash)))
          withClue(s"minerHash=$minerHash prevHash=$prevHash: ") {
            d.appendBlockE(kb) should beRight
          }
        }
    }
  }
}
