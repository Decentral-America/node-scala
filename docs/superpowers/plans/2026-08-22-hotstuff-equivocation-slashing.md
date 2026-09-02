> **SUPERSEDED** by `docs/superpowers/plans/2026-09-01-hotstuff-equivocation-evidence.md` + spec rev.2.
> This plan folded detected equivocators straight into `FinalizationVoting.conflict` with no
> receive-side proof validation or union step — a receiving node had no way to independently verify
> an equivocation claim, only trust whatever the block's miner asserted. The evidence design replaces
> this with proof-carried exclusion: a verified `HotStuffEquivocationProof` (both conflicting votes,
> re-verified against the committee) travels on the wire, and every receiving node validates it for
> itself (`validateFinalizationVoting`, feature 29) before the voter is unioned into
> `conflictGenerators` — no trust-the-miner step anywhere in the path.

# HotStuff Equivocation → Slashing Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire HotStuff's already-working equivocation detector (`HotStuffSafety.equivocators`, double-signing within a `(voter, view, phase)`) into the existing T0 `conflictGenerators` stake-penalty mechanism, so a double-signing committee member actually loses stake — this is T5 in `docs/hotstuff-audit-readiness.md` and is explicitly listed as a required gate before HotStuff can be made authoritative on mainnet.

**Architecture:** HotStuff's `voterIndex` and T0's `GeneratorIndex`/`endorserIndex` are confirmed to be the same index space (both resolve against `blockchain.committedGenerators(period)` for the same generation period — verified via `HotStuffQuorum.scala:65`). This means detected equivocators can be folded into the same `FinalizationVoting.conflict` → `calculatePenalties` → `-DepositInDcclets` pipeline that T0 conflict endorsements already use, rather than building a second, parallel slashing mechanism. The wiring is gated behind a new `dcc.hotstuff.slashing.enabled` config flag (default `false`), following the exact pattern `dcc.hotstuff.authoritative` already established for HotStuff feature rollout — this keeps the feature buildable and testable now without it taking effect on any live chain (including testnet) until explicitly enabled post-audit, since T5 wiring is itself audit-gated per the security review.

**Tech Stack:** Scala 2.13, existing `HotStuffSafety`/`HotStuffEngine`/`HotStuffCoordinator` pure-core + engine split, existing `FinalizationVoting`/`conflictGenerators` T0 mechanism, ScalaTest.

## Global Constraints

- `dcc.hotstuff.slashing.enabled` MUST default to `false` in every config (testnet, stagenet, mainnet) until this plan's Task 4 verification is complete AND an external audit sign-off exists — do not flip it live as part of this plan.
- Do not modify `HotStuffSafety.equivocators` itself — it's already correct (proven by `HotStuffSafetySpecification`); this plan only wires its output somewhere.
- New wire-format additions (if any) must be backward compatible the same way `committeeEpoch` was (proto3 field with a safe default, documented in `docs/hotstuff-audit-readiness.md` T10 entry) — do not break decoding for peers running an older version.
- This feature is itself in scope for the external audit (`docs/hotstuff-audit-readiness.md` — "Out of scope: ... making HotStuff authoritative (not built)" no longer applies once this exists, so flag it as new audit surface, don't bury it).

---

## File Structure

- Create: `node-scala/node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationEvidence.scala` — the evidence record (two conflicting votes from the same voter) and its validation.
- Modify: `node-scala/node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffCoordinator.scala` — surface detected equivocators from the live vote pool as `HotStuffEquivocationEvidence` instead of discarding them.
- Modify: `node-scala/node/src/main/scala/com/decentralchain/Application.scala` — wire the coordinator's evidence stream into block production (feeding `FinalizationVoting.conflict` when this node is the next block's miner), gated on `dcc.hotstuff.slashing.enabled`.
- Modify: `node-scala/node/src/main/scala/com/decentralchain/settings/*.scala` (wherever `dcc.hotstuff.authoritative` is declared — add the sibling `slashing.enabled` flag next to it).
- Test: `node-scala/node/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationEvidenceSpecification.scala`
- Test: `node-scala/node/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationWiringSpecification.scala`

---

### Task 1: Equivocation evidence record + validation

**Files:**
- Create: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationEvidence.scala`
- Test: `node/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationEvidenceSpecification.scala`

**Interfaces:**
- Consumes: `HotStuffVote` (existing type — has `voterIndex: Int`, `view: Int`, `phase: HotStuffPhase`, `blockId: BlockId`, and a BLS signature field; check exact field names in `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffVote.scala` before writing this task's code, adjusting names to match).
- Produces: `HotStuffEquivocationEvidence(voterIndex: Int, view: Int, phase: HotStuffPhase, voteA: HotStuffVote, voteB: HotStuffVote)` with a `verified: Boolean` method other tasks call — this is the type Task 2 and Task 3 both consume.

- [ ] **Step 1: Check the real `HotStuffVote` shape**

```bash
cat node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffVote.scala
```

Confirm the exact field names (`voterIndex`, `view`, `phase`, `blockId`, signature field name) before writing Step 2 — adjust the code below to match if names differ.

- [ ] **Step 2: Write the failing test**

```scala
package com.decentralchain.consensus.hotstuff

import com.decentralchain.crypto.bls.BlsKeyPair
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class HotStuffEquivocationEvidenceSpecification extends AnyFreeSpec with Matchers {
  val kp = BlsKeyPair.generate()

  "HotStuffEquivocationEvidence" - {
    "is verified when both votes are from the same voter/view/phase but different blockIds, each with a valid signature" in {
      val blockA = ByteStr(Array.fill(32)(1: Byte))
      val blockB = ByteStr(Array.fill(32)(2: Byte))
      val voteA  = HotStuffCoordinatorTestKit.signedVote(kp, voterIndex = 3, view = 10, phase = HotStuffPhase.Prepare, blockId = blockA)
      val voteB  = HotStuffCoordinatorTestKit.signedVote(kp, voterIndex = 3, view = 10, phase = HotStuffPhase.Prepare, blockId = blockB)

      val evidence = HotStuffEquivocationEvidence(3, 10, HotStuffPhase.Prepare, voteA, voteB)
      evidence.verified(kp.publicKey) shouldBe true
    }

    "is NOT verified when the two votes are for the same blockId (not actually a conflict)" in {
      val blockA = ByteStr(Array.fill(32)(1: Byte))
      val voteA  = HotStuffCoordinatorTestKit.signedVote(kp, voterIndex = 3, view = 10, phase = HotStuffPhase.Prepare, blockId = blockA)
      val voteB  = HotStuffCoordinatorTestKit.signedVote(kp, voterIndex = 3, view = 10, phase = HotStuffPhase.Prepare, blockId = blockA)

      val evidence = HotStuffEquivocationEvidence(3, 10, HotStuffPhase.Prepare, voteA, voteB)
      evidence.verified(kp.publicKey) shouldBe false
    }

    "is NOT verified when either vote's signature doesn't check out against the claimed voter's key" in {
      val otherKp = BlsKeyPair.generate()
      val blockA  = ByteStr(Array.fill(32)(1: Byte))
      val blockB  = ByteStr(Array.fill(32)(2: Byte))
      val voteA   = HotStuffCoordinatorTestKit.signedVote(kp, voterIndex = 3, view = 10, phase = HotStuffPhase.Prepare, blockId = blockA)
      val voteB   = HotStuffCoordinatorTestKit.signedVote(kp, voterIndex = 3, view = 10, phase = HotStuffPhase.Prepare, blockId = blockB)

      val evidence = HotStuffEquivocationEvidence(3, 10, HotStuffPhase.Prepare, voteA, voteB)
      evidence.verified(otherKp.publicKey) shouldBe false // wrong key
    }
  }
}
```

Note: `HotStuffCoordinatorTestKit.signedVote` is a small helper you'll need to add (or find an equivalent already used in `HotStuffQuorumSpecification`/`HotStuffSafetySpecification` — check those files first, they almost certainly already build test votes; reuse that helper rather than writing a new one).

- [ ] **Step 3: Run test to verify it fails**

```bash
sbt "node/testOnly com.decentralchain.consensus.hotstuff.HotStuffEquivocationEvidenceSpecification"
```

Expected: FAIL with "object HotStuffEquivocationEvidence not found" (or similar compile error).

- [ ] **Step 4: Write the implementation**

```scala
package com.decentralchain.consensus.hotstuff

import com.decentralchain.crypto.bls.BlsPublicKey

/** Proof that `voterIndex` double-signed within a single (view, phase) — the two votes
  * target different blockIds, which HotStuffSafety.equivocators already detects by
  * grouping live pool votes on (voterIndex, view, phase). This record makes that
  * detection independently verifiable by anyone (e.g. the next block's miner, or a
  * later auditor) without needing to have observed the live vote pool themselves.
  */
case class HotStuffEquivocationEvidence(
    voterIndex: Int,
    view: Int,
    phase: HotStuffPhase,
    voteA: HotStuffVote,
    voteB: HotStuffVote
) {
  def verified(voterPublicKey: BlsPublicKey): Boolean =
    voteA.voterIndex == voterIndex &&
      voteB.voterIndex == voterIndex &&
      voteA.view == view && voteB.view == view &&
      voteA.phase == phase && voteB.phase == phase &&
      voteA.blockId != voteB.blockId &&
      voteA.signatureValid(voterPublicKey) &&
      voteB.signatureValid(voterPublicKey)
}
```

Adjust `voteA.signatureValid(...)` to whatever `HotStuffVote`'s actual signature-check method is named (check Step 1's findings) — if it doesn't already expose one, add it following the same pattern as `BlockEndorsement.signatureValid` (`node/src/main/scala/com/decentralchain/block/BlockEndorsement.scala:14`).

- [ ] **Step 5: Run test to verify it passes**

```bash
sbt "node/testOnly com.decentralchain.consensus.hotstuff.HotStuffEquivocationEvidenceSpecification"
```

Expected: PASS, all 3 cases.

- [ ] **Step 6: Commit**

```bash
git add node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationEvidence.scala \
        node/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationEvidenceSpecification.scala
git commit -m "feat(hotstuff): add verifiable equivocation evidence record (T5, step 1/3)"
```

---

### Task 2: Surface evidence from the live coordinator instead of discarding it

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffCoordinator.scala`
- Test: `node/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationWiringSpecification.scala`

**Interfaces:**
- Consumes: `HotStuffEquivocationEvidence` from Task 1, `HotStuffSafety.equivocators(votes: Iterable[HotStuffVote]): Set[Int]` (existing, unchanged, `HotStuffSafety.scala:96-100`).
- Produces: `HotStuffCoordinator.detectedEquivocations: Seq[HotStuffEquivocationEvidence]` — a queryable, append-only list the miner path (Task 3) reads from when building the next block.

- [ ] **Step 1: Read the current `onVote` handling to find the right hook point**

```bash
grep -n "def onVote\|equivocators\|votePool" node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffCoordinator.scala | head -30
```

Identify where the live vote pool for a given `(view, phase)` bucket is available as an `Iterable[HotStuffVote]` — `HotStuffSafety.equivocators` needs exactly that shape, and `HotStuffCoordinator.scala:280` (`bucket.map(_.voterIndex)...`) shows a `bucket` value already exists at the right point.

- [ ] **Step 2: Write the failing test**

```scala
package com.decentralchain.consensus.hotstuff

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class HotStuffEquivocationWiringSpecification extends AnyFreeSpec with Matchers {
  "HotStuffCoordinator" - {
    "records equivocation evidence when the same voter signs two different blockIds for the same (view, phase)" in {
      val kp          = HotStuffTestKit.generateKeyPair()
      val coordinator = HotStuffTestKit.newCoordinatorWithVoter(voterIndex = 2, keyPair = kp)

      val voteA = HotStuffTestKit.signedVote(kp, voterIndex = 2, view = 5, phase = HotStuffPhase.PreCommit, blockId = HotStuffTestKit.blockId(1))
      val voteB = HotStuffTestKit.signedVote(kp, voterIndex = 2, view = 5, phase = HotStuffPhase.PreCommit, blockId = HotStuffTestKit.blockId(2))

      coordinator.onVote(voteA)
      coordinator.onVote(voteB)

      coordinator.detectedEquivocations.map(e => (e.voterIndex, e.view, e.phase)) should contain((2, 5, HotStuffPhase.PreCommit))
    }

    "does not record anything when all votes in a bucket target the same blockId" in {
      val kp          = HotStuffTestKit.generateKeyPair()
      val coordinator = HotStuffTestKit.newCoordinatorWithVoter(voterIndex = 2, keyPair = kp)

      val voteA = HotStuffTestKit.signedVote(kp, voterIndex = 2, view = 5, phase = HotStuffPhase.PreCommit, blockId = HotStuffTestKit.blockId(1))
      coordinator.onVote(voteA)

      coordinator.detectedEquivocations shouldBe empty
    }
  }
}
```

`HotStuffTestKit` here is a stand-in name — check `HotStuffCoordinator.scala`'s existing test file (`HotStuffCoordinatorSpecification.scala` if it exists, or the DST harness from `docs/superpowers/plans/2026-07-24-hotstuff-dst-harness.md`) for whatever test-construction helpers already exist for building a coordinator + signed votes, and use those real names instead of inventing `HotStuffTestKit`.

- [ ] **Step 3: Run test to verify it fails**

```bash
sbt "node/testOnly com.decentralchain.consensus.hotstuff.HotStuffEquivocationWiringSpecification"
```

Expected: FAIL — `detectedEquivocations` doesn't exist yet.

- [ ] **Step 4: Implement**

At the point identified in Step 1 (after a vote is added to `bucket`), add:

```scala
private var _detectedEquivocations: Vector[HotStuffEquivocationEvidence] = Vector.empty
def detectedEquivocations: Seq[HotStuffEquivocationEvidence] = _detectedEquivocations

// After updating `bucket` with the new vote, inside the same onVote handling:
val equivocatorIndexes = HotStuffSafety.equivocators(bucket)
if (equivocatorIndexes.nonEmpty) {
  equivocatorIndexes.foreach { idx =>
    val votesFromIdx = bucket.filter(_.voterIndex == idx)
    // Two votes for different blockIds are enough to construct evidence; if more than
    // two exist (shouldn't happen under correct dedup, but be defensive), take the first two distinct.
    votesFromIdx.map(_.blockId).distinct.take(2) match {
      case Seq(_, _) =>
        val Seq(voteA, voteB) = votesFromIdx.groupBy(_.blockId).values.map(_.head).take(2).toSeq
        val alreadyRecorded = _detectedEquivocations.exists(e => e.voterIndex == idx && e.view == voteA.view && e.phase == voteA.phase)
        if (!alreadyRecorded) {
          _detectedEquivocations = _detectedEquivocations :+ HotStuffEquivocationEvidence(idx, voteA.view, voteA.phase, voteA, voteB)
        }
      case _ => ()
    }
  }
}
```

Adjust variable names (`bucket`, the enclosing method) to match exactly what Step 1 found — this plan can't know the precise surrounding code structure until that step runs.

- [ ] **Step 5: Run test to verify it passes**

```bash
sbt "node/testOnly com.decentralchain.consensus.hotstuff.HotStuffEquivocationWiringSpecification"
```

- [ ] **Step 6: Run the full HotStuff suite to check nothing else broke**

```bash
sbt "node/testOnly com.decentralchain.consensus.hotstuff.*"
```

- [ ] **Step 7: Commit**

```bash
git add node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffCoordinator.scala \
        node/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationWiringSpecification.scala
git commit -m "feat(hotstuff): surface detected equivocators as verifiable evidence (T5, step 2/3)"
```

---

### Task 3: Feed evidence into `FinalizationVoting.conflict` behind the slashing flag

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/Application.scala`
- Modify: wherever `dcc.hotstuff.authoritative` is declared (find via `grep -rn "hotstuff.authoritative" node/src/main/scala/com/decentralchain/settings/`)

**Interfaces:**
- Consumes: `HotStuffCoordinator.detectedEquivocations` from Task 2, existing `FinalizationVoting(valid: Seq[GeneratorIndex], conflict: Seq[BlockEndorsement])` (`node/src/main/scala/com/decentralchain/block/FinalizationVoting.scala:8-12`).
- Produces: when `dcc.hotstuff.slashing.enabled = true` and this node is producing the next block, any pending `detectedEquivocations` for the relevant committee get folded into that block's `FinalizationVoting.conflict`, triggering the existing `calculatePenalties` deduction the next time `checkCommittedGeneratorsHash`'s period rolls over.

- [ ] **Step 1: Add the config flag**

```bash
grep -n "hotstuff.authoritative\|hotstuffAuthoritative" node/src/main/scala/com/decentralchain/settings/*.scala
```

Add `slashingEnabled: Boolean` as a sibling field in whatever `case class` holds `authoritative` (exact file/name depends on Step 1's grep result), defaulting to `false`, and add the corresponding `dcc.hotstuff.slashing.enabled = no` line to every `.conf` file that currently sets `dcc.hotstuff.authoritative` (testnet, and the mainnet/stagenet templates once they exist) — grep for `hotstuff.authoritative` across `*.conf` files to find them all.

- [ ] **Step 2: Find where the block-production path builds `FinalizationVoting` today**

```bash
grep -rn "FinalizationVoting(" node/src/main/scala/com/decentralchain/ | grep -v test
```

This is the exact call site Step 3 needs to modify — it already assembles `valid` (from T0's existing endorsement mechanism); this task adds `conflict` entries alongside it.

- [ ] **Step 3: Write the failing test**

The exact test depends on Step 2's findings (which method builds `FinalizationVoting` and what its test harness looks like already — check for an existing `MinerSpecification` or `BlockAppenderSpecification` style test file testing that construction). Structure it as:

```scala
"when dcc.hotstuff.slashing.enabled is true and a pending equivocation evidence exists for the current committee, " +
"the next produced block's FinalizationVoting.conflict includes a BlockEndorsement-shaped conflict entry for that voterIndex" in {
  // Arrange: a test blockchain with slashingEnabled = true, a coordinator with one
  // recorded HotStuffEquivocationEvidence for voterIndex = 1.
  // Act: produce the next block via whatever method Step 2 identified.
  // Assert: block.header.finalizationVoting.get.conflict.exists(_.endorserIndex == GeneratorIndex(1))
}

"when dcc.hotstuff.slashing.enabled is false (default), detected equivocations are NOT folded into FinalizationVoting.conflict" in {
  // Same arrange, but slashingEnabled = false.
  // Assert: block.header.finalizationVoting.get.conflict is empty (or unaffected by the equivocation evidence).
}
```

- [ ] **Step 4: Run test to verify it fails**

```bash
sbt "node/testOnly <the test class from Step 3>"
```

- [ ] **Step 5: Implement the wiring**

At the call site found in Step 2, before constructing `FinalizationVoting`:

```scala
val equivocationConflictEntries: Seq[BlockEndorsement] =
  if (settings.dccSettings.hotstuff.slashingEnabled) {
    hotStuffCoordinator.detectedEquivocations
      .filter(e => committee.exists(_.index.toInt == e.voterIndex)) // only current committee members
      .flatMap { evidence =>
        committee.find(_.index.toInt == evidence.voterIndex).flatMap { generatorIndex =>
          // Reuses BlockEndorsement's existing shape so it flows through the unmodified
          // calculatePenalties/conflictGenerators pipeline exactly like a T0-native conflict.
          // finalizedId/finalizedHeight/endorsedId are populated from evidence.voteA/voteB's
          // targets -- adjust field mapping once Task 1's exact HotStuffVote shape is confirmed.
          Some(BlockEndorsement(generatorIndex, /* finalizedId */ evidence.voteA.blockId, /* finalizedHeight */ currentHeight, /* endorsedId */ evidence.voteB.blockId, /* signature */ evidence.voteA.signature))
        }
      }
  } else Seq.empty

// existing FinalizationVoting(...) construction, extended:
FinalizationVoting(valid = existingValidEndorsements, conflict = existingConflictEntries ++ equivocationConflictEntries)
```

This step's exact field-mapping (`finalizedId`/`endorsedId`/`signature`) needs verification against `BlockEndorsement.signatureValid`'s actual check (`BlockEndorsement.scala:14-15`) — `BlockEndorsement`'s signature is over `mkMessage(finalizedId, finalizedHeight, endorsedId)`, which is a DIFFERENT message shape than a HotStuff vote signs. **This means `evidence.voteA.signature` cannot be dropped into `BlockEndorsement.signature` as-is — it won't verify.** Two real options, pick one and note the choice in the commit message:
  - (a) Add a new `BlockEndorsement`-sibling variant specifically for HotStuff-sourced conflicts that carries the raw `HotStuffEquivocationEvidence` and has its own `signatureValid` (checking against the BLS vote message shape, not `BlockEndorsement.mkMessage`), and extend `FinalizationVoting.conflict`'s type to accept either — this is more invasive (touches the wire format) but is honest about the two having different provenance.
  - (b) Have the reporting node itself re-sign a proper `BlockEndorsement` attesting "I observed voterIndex X equivocate at (view, phase)", using the reporting node's own miner key, with `finalizedId`/`endorsedId` repurposed to carry `voteA.blockId`/`voteB.blockId` as the evidence payload, and validate elsewhere that these fields decode back to genuine differing HotStuff vote targets. This keeps `FinalizationVoting.conflict`'s type unchanged but is a data-shape hack.

Given this is new consensus wire format touching audit scope, do not silently pick (a) or (b) — write up both in `docs/hotstuff-audit-readiness.md` as a new open decision under T5 and get a second human/team read before locking in the wire format, since whichever is chosen becomes part of what the external audit reviews.

- [ ] **Step 6: Run test, confirm pass**

```bash
sbt "node/testOnly <the test class from Step 3>"
```

- [ ] **Step 7: Commit**

```bash
git add <files touched>
git commit -m "feat(hotstuff): wire equivocation evidence into FinalizationVoting.conflict behind dcc.hotstuff.slashing.enabled (T5, step 3/3)"
```

---

### Task 4: Update audit-readiness docs and confirm the flag stays off everywhere live

**Files:**
- Modify: `docs/hotstuff-audit-readiness.md`
- Modify: `docs/hotstuff-security-review.md`

**Interfaces:**
- Consumes: Tasks 1-3's implementation.
- Produces: an updated T5 row reflecting "wired, gated behind `dcc.hotstuff.slashing.enabled=false`, pending audit before enabling" instead of "future work" — and a repo-wide confirmation the flag isn't accidentally live anywhere.

- [ ] **Step 1: Confirm the flag is off everywhere**

```bash
grep -rn "hotstuff.slashing.enabled" --include="*.conf" .
```

Expected: every match shows `= no` / `= false`. If any live testnet config shows `yes`/`true`, that's a mistake — fix it before merging.

- [ ] **Step 2: Update `docs/hotstuff-audit-readiness.md`'s T5 row**

Change:
```
| T5 | Equivocation (double-sign) | `HotStuffSafety.equivocators` | detected; slashing/`conflictGenerators` wiring is future work |
```
to:
```
| T5 | Equivocation (double-sign) | `HotStuffSafety.equivocators` + `HotStuffEquivocationEvidence` | detected AND wired into `conflictGenerators` via `FinalizationVoting.conflict`; gated behind `dcc.hotstuff.slashing.enabled` (default false) pending external audit sign-off before enabling anywhere live |
```

Also update the "What remains before mainnet" checklist item that currently reads "(2) equivocation → `conflictGenerators` slashing wiring" to reflect it's now built-but-gated, not unbuilt.

- [ ] **Step 3: Update `docs/hotstuff-security-review.md` finding #5**

Change the "[INFO] Equivocation → slashing integration" finding from "is engine work (3c/4)" to reference this plan's commits and flag the Task 3, Step 5 wire-format decision as new audit surface requiring explicit reviewer attention.

- [ ] **Step 4: Commit**

```bash
git add docs/hotstuff-audit-readiness.md docs/hotstuff-security-review.md
git commit -m "docs(hotstuff): update T5 status now that equivocation-to-slashing wiring is built (gated, unaudited)"
```
