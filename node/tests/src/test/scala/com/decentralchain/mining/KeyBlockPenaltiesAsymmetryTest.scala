package com.decentralchain.mining

import com.decentralchain.block.{Block, BlockEndorsement, FinalizationVoting}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.state.diffs.BlockDiffer
import com.decentralchain.state.{Blockchain, GeneratorIndex, Height, Portfolio, StateSnapshot, TxStateSnapshotHashBuilder}
import com.decentralchain.account.Address
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.transaction.CommitToGenerationTransaction
import com.decentralchain.state.patch.{CancelAllLeases, CancelInvalidLeaseIn, CancelLeaseOverflow, CancelLeasesToDisabledAliases}

/** Task 8b step 3. Drives the PENALTIES path of the key-block initial snapshot -- the one hash input
  * the miner and the appender reach through two DIFFERENT `BlockDiffer.calculatePenalties`
  * overloads:
  *
  *   - `BlockDiffer.scala:380`, `calculatePenalties(blockchain, prevBlockId: BlockId)`, used by the
  *     MINER via `createInitialBlockSnapshot` (`:458`), which resolves the voting indirectly:
  *     `heightOf(prevBlockId)` -> `blockHeader(height)` -> `header.finalizationVoting`.
  *   - `BlockDiffer.scala:393`, `calculatePenalties(blockchain, prevBlock: Block)`, used by the
  *     APPENDER via `fromBlockTraced` (`:218`), which reads `prevBlock.header.finalizationVoting`
  *     directly off the in-memory forged Block object.
  *
  * Both then delegate to the shared `:406` overload. The two differ in their field-read ORDER and,
  * more importantly, in the SOURCE of `voting`. If those two sources can ever disagree for the same
  * tip, the miner's and appender's initial snapshots differ by a whole `DepositInDcclets` per
  * divergent conflict entry, and the two state hashes diverge deterministically -- which is exactly
  * the signature of the 2026-09-01 height-2640 stall.
  *
  * Every pre-existing test leaves `finalizationVoting` empty, so `calculatePenalties` folds over an
  * empty `conflict` sequence and returns `Map.empty` on BOTH sides: the asymmetry is inert and
  * unobservable. These tests make it observable.
  *
  * The two overloads are `private`, so they are reached reflectively. That is deliberate: driving
  * them directly isolates THIS mechanism, instead of testing whether a synthetic conflict
  * endorsement can satisfy the long chain of unrelated append-time guards
  * (`validateFinalizationVoting`'s committed-generator, balance, and signature checks), which would
  * test the scaffolding rather than the code under investigation.
  */
class KeyBlockPenaltiesAsymmetryTest extends FreeSpec with WithDomain {

  private val richAccount = TxHelpers.signer(1)

  /** Must NOT be the miner (`defaultSigner`): `validateConflictingEndorsement` rejects a conflicting
    * endorsement whose committed address is the block's own generator.
    */
  private val endorserAccount = TxHelpers.signer(77)

  /** The committee member that actually generates, distinct from `endorserAccount`. */
  private val minerAccount = TxHelpers.signer(78)

  private val differClass = Class.forName("com.decentralchain.state.diffs.BlockDiffer$")
  private val differ      = differClass.getField("MODULE$").get(null)

  private def penaltiesByBlockId(bc: Blockchain, id: ByteStr): Either[String, Map[Address, Portfolio]] = {
    val m = differClass.getDeclaredMethods
      .find(mm => mm.getName.contains("calculatePenalties") && mm.getParameterCount == 2 && mm.getParameterTypes()(1) == classOf[ByteStr])
      .get
    m.setAccessible(true)
    m.invoke(differ, bc, id).asInstanceOf[Either[String, Map[Address, Portfolio]]]
  }

  private def penaltiesByBlock(bc: Blockchain, b: Block): Either[String, Map[Address, Portfolio]] = {
    val m = differClass.getDeclaredMethods
      .find(mm => mm.getName.contains("calculatePenalties") && mm.getParameterCount == 2 && mm.getParameterTypes()(1) == classOf[Block])
      .get
    m.setAccessible(true)
    m.invoke(differ, bc, b).asInstanceOf[Either[String, Map[Address, Portfolio]]]
  }

  "the two calculatePenalties overloads agree" - {

    "on a BASE key block tip with no voting (the inert baseline every existing test hits)" in
      withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(richAccount)) { d =>
        d.appendBlock()
        d.appendBlock()

        val baseId = d.liquidState.get.base.id()
        val forged = d.liquidState.get.liquidBlockOf(baseId).get.block
        val bc     = d.blockchainUpdater.referencedBlockchain(baseId)

        val byId    = penaltiesByBlockId(bc, baseId)
        val byBlock = penaltiesByBlock(bc, forged)

        withClue(s"byId=$byId byBlock=$byBlock: ") { byId shouldBe byBlock }
      }

    "on a MICROBLOCK tip with no voting" in
      withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(richAccount)) { d =>
        d.appendBlock()
        d.appendBlock()
        d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
        val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))

        val forged = d.liquidState.get.liquidBlockOf(microTip).get.block
        val bc     = d.blockchainUpdater.referencedBlockchain(microTip)

        forged.id() shouldBe microTip

        val byId    = penaltiesByBlockId(bc, microTip)
        val byBlock = penaltiesByBlock(bc, forged)

        withClue(s"byId=$byId byBlock=$byBlock: ") { byId shouldBe byBlock }
      }

    /** The load-bearing case. A conflict endorsement is injected into the liquid period's voting so
      * that `calculatePenalties` actually folds over a NON-EMPTY `conflict` sequence -- the state no
      * existing test constructs. Both overloads are then driven against the identical blockchain and
      * the identical tip, and must produce the identical penalty map.
      */
    "on a MICROBLOCK tip whose accumulated voting CARRIES CONFLICTS" in
      withDomain(
        // Periods of length 2 ([3,4], [5,6], [7,8], ...) so a commitment can actually take effect
        // inside a short test chain -- the default 3000 puts the first committee at height 3001.
        DeterministicFinality.configure(_.copy(generationPeriodLength = 2)),
        Seq(
          AddrWithBalance(richAccount.toAddress, 1000000.dcc),
          AddrWithBalance(endorserAccount.toAddress, 1000000.dcc),
          AddrWithBalance(minerAccount.toAddress, 1000000.dcc)
        )
      ) { d =>
        // TWO real committed generators, so `committedGenerators` is non-empty and an endorser index
        // actually resolves to an address (otherwise `calculatePenalties` can only ever return an
        // empty map or an "invalid index" error, and the comparison is vacuous). Two are needed
        // because once a committee is live only its members may generate, while
        // `validateConflictingEndorsement` forbids a conflicting endorsement from the block's own
        // generator -- so the miner and the conflicting endorser must be different committee members.
        // The commitment names the NEXT period's start, and that committee is only live DURING that
        // period -- so mine just far enough to be inside it, not past it.
        d.appendBlock(
          TxHelpers.commitToGeneration(Height(3), endorserAccount),
          TxHelpers.commitToGeneration(Height(3), minerAccount)
        )
        // Once the committee is live only its members may generate, so this must come from
        // `minerAccount` rather than the default signer.
        d.appendBlock(d.createBlock(Block.PlainBlockVersion, Nil, generator = minerAccount))
        d.appendBlock(d.createBlock(Block.PlainBlockVersion, Nil, generator = minerAccount))

        val curr      = d.blockchain.currentGenerationPeriod
        val committee = curr.map(p => d.blockchain.committedGenerators(p))
        info(s"height=${d.blockchain.height} currentGenerationPeriod=$curr")
        info(s"committedGenerators(curr)=$committee")
        info(s"committedGenerators(curr.next)=${curr.map(p => d.blockchain.committedGenerators(p.next))}")
        withClue(s"a live committee is required for a penalty to resolve, got $committee: ") {
          committee.exists(_.nonEmpty) shouldBe true
        }

        val baseId     = d.liquidState.get.base.id()
        val finalizedH = Height(d.blockchain.height - 2)
        // A CONFLICTING endorsement is precisely one that names a DIFFERENT block at the finalized
        // height than the canonical one -- `validateConflictingEndorsement` rejects it as
        // "Contains expected finalized block" if the id matches the real chain.
        val canonicalFinalizedId = d.blockchain.blockHeader(finalizedH.toInt).get.id()
        val finalizedId          = ByteStr(canonicalFinalizedId.arr.updated(0, (canonicalFinalizedId.arr(0) ^ 0xff).toByte))

        def conflictVoting(idx: Int) =
          FinalizationVoting(
            valid = Seq.empty,
            finalizedHeight = finalizedH,
            aggregatedEndorsement = None,
            conflict = Seq(
              BlockEndorsement
                .signed(BlsKeyPair(endorserAccount.privateKey), GeneratorIndex(idx), finalizedId, finalizedH, baseId)
            )
          )

        // Land a microblock that ACTUALLY CARRIES the conflict voting, straight through
        // `processMicroBlock`, so the liquid tip is a real chain-known id whose accumulated voting
        // is non-empty. Both overloads gate on `heightOf(id)`, so the conflict has to arrive on a
        // block the chain has really seen -- grafting it onto a copy changes `protoHeaderHash` and
        // therefore the id, and both sides then return an empty map for the wrong reason.
        // The microblock must be signed by the BASE BLOCK's generator, which is `minerAccount` here.
        val mbWithConflict = d.createMicroBlock(signer = Some(minerAccount), finalizationVoting = Some(conflictVoting(0)))(
          TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc)
        )
        val microTip = d.blockchainUpdater.processMicroBlock(mbWithConflict, None) match {
          case Right(id) => id
          case Left(err) =>
            cancel(
              s"could not land a conflict-carrying microblock ($err) -- the append-time endorsement " +
                s"guards need scaffolding this test deliberately does not build; see the report"
            )
        }

        val forgedWithConflict = d.liquidState.get.liquidBlockOf(microTip).get.block

        withClue("the forged liquid block must carry the conflict, else this test is vacuous: ") {
          forgedWithConflict.header.finalizationVoting.map(_.conflict.size) shouldBe Some(1)
        }

        val bc = d.blockchainUpdater.referencedBlockchain(microTip)

        // APPENDER overload: reads voting straight off the Block object -- sees the conflict.
        val byBlock = penaltiesByBlock(bc, forgedWithConflict)

        // MINER overload: must resolve heightOf(id) then blockHeader(height) to find the SAME
        // voting. If that indirection cannot see this block's voting, penalties silently come back
        // empty while the appender's do not -- a deterministic, systematic divergence.
        val byId = penaltiesByBlockId(bc, microTip)

        // Diagnose the preconditions each overload depends on, so a Right(Map()) on both sides
        // cannot be mistaken for agreement when it really means "neither side saw the voting".
        val hId     = bc.heightOf(microTip)
        val hdrVote = hId.flatMap(h => bc.blockHeader(h)).flatMap(_.header.finalizationVoting)
        info(s"forged.id()=${forgedWithConflict.id()} baseId=$baseId")
        info(s"heightOf(forged.id()) [miner overload step 1] = $hId")
        info(s"blockHeader(h).finalizationVoting [miner overload step 2] = $hdrVote")
        info(s"forged.header.finalizationVoting [appender overload] = ${forgedWithConflict.header.finalizationVoting}")
        info(s"generationPeriodOf = ${hId.flatMap(h => bc.generationPeriodOf(Height(h)))}")
        info(s"committedGenerators(period) = ${hId.flatMap(h => bc.generationPeriodOf(Height(h))).map(p => bc.committedGenerators(p))}")
        info(s"appender-side (by Block)   penalties: $byBlock")
        info(s"miner-side    (by BlockId) penalties: $byId")

        // NON-VACUITY: the whole point is that a real penalty was actually levied. Without this the
        // comparison below would pass trivially on two empty maps, which is how this asymmetry has
        // stayed invisible in every other test.
        withClue(s"penalties must be non-empty for this comparison to mean anything, got $byBlock: ") {
          byBlock.map(_.size) shouldBe Right(1)
          byBlock.map(_.values.map(_.balance).sum) shouldBe Right(-CommitToGenerationTransaction.DepositInDcclets)
        }

        withClue(s"byId=$byId byBlock=$byBlock: ") { byId shouldBe byBlock }

        // END-TO-END, on this same penalty-bearing state: the miner's forge-time hash must survive
        // the appender's real recompute. This is the incident's exact failure mode
        // (`InvalidStateHash(expected, computed)` on sealing a key block over a liquid period).
        val prevHash     = d.blockchainUpdater.lastStateHash(Some(microTip))
        val initSnapshot =
          BlockDiffer.createInitialBlockSnapshot(d.blockchainUpdater, microTip, minerAccount.toAddress).explicitGet()
        val minerHash =
          if (initSnapshot == StateSnapshot.empty) prevHash
          else TxStateSnapshotHashBuilder.createHashFromSnapshot(initSnapshot, None).createHash(prevHash)

        val kb = d.createBlock(
          Block.NgBlockVersion,
          Nil,
          ref = Some(microTip),
          generator = minerAccount,
          stateHash = Some(Some(minerHash))
        )
        withClue(s"minerHash=$minerHash prevHash=$prevHash initSnapshot=$initSnapshot: ") {
          d.appendBlockE(kb) should beRight
        }
      }
  }

  /** Side-finding from the step 1-2 round, now closed: the appender applies `leasePatchesSnapshot`
    * (`BlockDiffer.scala:222`) while the miner's `createInitialBlockSnapshot` does not. The shape of
    * that asymmetry is real, but it is provably INERT on every DCC chain, so it cannot be the cause
    * of the height-2640 divergence:
    *
    *   - `CancelAllLeases`, `CancelLeaseOverflow` and `CancelInvalidLeaseIn` all extend
    *     `PatchAtHeight()` with an EMPTY chain-id/height map, so `isDefinedAt` is false everywhere.
    *   - `CancelLeasesToDisabledAliases` extends `PatchOnFeature(SynchronousCalls, Set.empty)`, and
    *     an empty `networks` set means "no network" (see the note on `PatchOnFeature`).
    *
    * If any patch ever gains a real activation entry, this test fails and the miner's path must be
    * brought back into line with the appender's.
    */
  "leasePatchesSnapshot is inert on this chain, so the miner/appender patch asymmetry cannot bite" in
    withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(richAccount)) { d =>
      d.appendBlock()
      d.appendBlock()

      val patches = Seq(CancelAllLeases, CancelLeaseOverflow, CancelInvalidLeaseIn, CancelLeasesToDisabledAliases)
      patches.foreach { p =>
        withClue(s"$p must not be defined at any height on this chain: ") {
          p.isDefinedAt(d.blockchain) shouldBe false
        }
      }
    }

  /** End-to-end guard, kept from the step 1-2 round: with no conflicts the miner's forge-time hash
    * and the appender's recompute agree on a microblock tip.
    */
  "miner forge-time hash matches the appender recompute on a microblock tip" in
    withDomain(DeterministicFinality, AddrWithBalance.enoughBalances(richAccount)) { d =>
      d.appendBlock()
      d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(2), 1.dcc))
      val microTip = d.appendMicroBlock(TxHelpers.transfer(richAccount, TxHelpers.address(3), 1.dcc))

      val miner        = com.decentralchain.history.defaultSigner.toAddress
      val prevHash     = d.blockchainUpdater.lastStateHash(Some(microTip))
      val initSnapshot = BlockDiffer.createInitialBlockSnapshot(d.blockchainUpdater, microTip, miner).explicitGet()
      val minerHash    =
        if (initSnapshot == StateSnapshot.empty) prevHash
        else TxStateSnapshotHashBuilder.createHashFromSnapshot(initSnapshot, None).createHash(prevHash)

      val kb = d.createBlock(Block.NgBlockVersion, Nil, ref = Some(microTip), stateHash = Some(Some(minerHash)))
      d.appendBlockE(kb) should beRight
    }
}
