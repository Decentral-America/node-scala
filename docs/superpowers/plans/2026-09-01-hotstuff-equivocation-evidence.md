# HotStuff Equivocation Evidence (T5, rev. 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detected T2 HotStuff equivocations become portable, block-carried, independently-verified evidence that deterministically excludes the offender's stake via the existing `conflictGenerators` mechanism — closing audit item T5 and BFT-audit finding F-3.

**Architecture:** Coordinator detects double-signs from its own vote pool and accumulates verified `HotStuffEquivocationProof`s (two conflicting signed votes, no claim). The miner folds pending proofs into the key block's `FinalizationVoting.hotstuffConflicts` (schema 1.6.6). Every node re-verifies each proof in `validateFinalizationVoting` (consistency, epoch = block period, both BLS signatures) and only then unions the voter index into `conflictGenerators` — in BOTH derivation layers (liquid `FinalizationState.append` and persisted `Caches.doAppend`). Gated on-chain by new feature 29; the `slashing-enabled` config flag gates evidence PRODUCTION only, never validation/union.

**Design SSOT:** `docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md` (rev. 2). Read it before starting — every rule below is justified there.

**Tech Stack:** Scala 3, ScalaTest, sbt, protobuf/scalapb (schema jar `io.decentralchain:protobuf-schemas`), GitHub Actions (schema publish).

## Global Constraints

- Reference implementation exists on unmerged branch `fix/height-3325-and-hotstuff-slashing` (commits `aac7a68928`, `456b7058ad`, `6d98b38394`, `62184efa67`). It is STALE vs `dev` (predates the upstream sync — e.g. `FinalizationVoting.withValid` changed shape) and is missing validation + union entirely. Use `git show <commit> -- <file>` as a pattern reference; NEVER cherry-pick it.
- Determinism contract (spec §5): `slashingEnabled` gates ONLY the miner-side fold. Proof validation and the `conflictGenerators` union are unconditional in this binary, gated only by feature-29 activation (chain state). Do not add any `slashingEnabled` check to `appender`, `FinalizationState`, or `Caches`.
- PB decode of a proof is STRICT: malformed or top-level-mismatched proofs throw (fail block parsing), never silently drop. This deliberately reverses the reference branch's decision (spec §2).
- No changes to `HotStuffSafety.equivocators`'s signature/logic, to vote/QC acceptance rules, or to T0's endorsement pipeline (`EndorsementStorage`, `BlockEndorser`, `MicroBlockMinerImpl` are untouched).
- Build/verify after every code task: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node/compile" "node-tests/testOnly com.decentralchain.consensus.hotstuff.* com.decentralchain.state.* com.decentralchain.mining.*"`.
- Before any push/PR: run the local quality gate (scalafmt + scalafix + `-Werror` compile) per this repo's convention.
- Commits are sole-authored by jourlez. No Co-Authored-By trailers, ever.
- Work on a feature branch off `dev` (e.g. `feat/hotstuff-equivocation-evidence`). Use a full clone if isolation is needed — NOT `git worktree` (sbt-git/JGit breaks under worktrees in this repo).

---

### Task 0: Publish protobuf-schemas 1.6.6 (DecentralChain monorepo — prerequisite)

**Repo:** `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/DecentralChain`

**Why:** Maven Central currently ends at 1.6.5 (verified: `https://repo1.maven.org/maven2/io/decentralchain/protobuf-schemas/maven-metadata.xml`). The 1.6.6 jar exists only in the local `~/.m2`. node-scala CI cannot build against 1.6.6 until this lands.

- [ ] **Step 1: Create a clean PR branch containing ONLY the two schema commits**

The existing branch `feat/hotstuff-equivocation-proof-schema` carries unrelated exchange-package commits. Do not PR it wholesale. Instead:

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/DecentralChain
git fetch origin
git checkout -b feat/protobuf-schemas-1.6.6 origin/main
git cherry-pick 959331f76 f1dd8a76b
```

If the cherry-picks conflict (main moved since 2026-08-22), resolve within `packages/sdk/protobuf-schemas/` only — the payload is: `proto/dcc/block.proto` gains `HotStuffEquivocationProof` + `FinalizationVoting.hotstuff_conflicts = 5`, `pom.xml` version `1.6.5` → `1.6.6`, `CHANGELOG.md` entry, regenerated TS codegen.

- [ ] **Step 2: Verify the proto payload matches the design**

Run: `git diff origin/main -- packages/sdk/protobuf-schemas/proto/dcc/block.proto`
Expected: exactly the `HotStuffEquivocationProof` message (`voter_index=1, view=2, phase=3, vote_a=4, vote_b=5`) and `repeated HotStuffEquivocationProof hotstuff_conflicts = 5;` in `FinalizationVoting`, plus comments. Nothing else.

- [ ] **Step 3: Push, open PR to `main`, merge after CI green**

```bash
git push -u origin feat/protobuf-schemas-1.6.6
gh pr create --title "feat(protobuf-schemas): HotStuffEquivocationProof + FinalizationVoting.hotstuff_conflicts (1.6.6)" --body "Schema for node-scala T5 equivocation evidence. Additive, proto3 default-empty. See node-scala docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md."
```

Monitor CI via GraphQL polling (never `gh run watch`).

- [ ] **Step 4: Dispatch the publish workflow and verify on Central**

After merge:

```bash
gh workflow run publish-protobuf-schemas.yml -f version=1.6.6
```

Then poll (publication to Central can take a while):

```bash
curl -s https://repo1.maven.org/maven2/io/decentralchain/protobuf-schemas/maven-metadata.xml | grep 1.6.6
```

Expected: `<version>1.6.6</version>` present. **Do not start Task 1's CI-dependent steps until this returns.** (Local development can proceed immediately — 1.6.6 is already in `~/.m2`.)

---

### Task 1: Dependency bump + `HotStuffEquivocationProof` record

**Repo:** `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala` (all remaining tasks)

**Files:**
- Modify: `project/Dependencies.scala:43` (`1.6.5` → `1.6.6`)
- Create: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationProof.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationProofSpecification.scala`

**Interfaces:**
- Consumes: `com.decentralchain.network.HotStuffVote` (`view: Int, phase: HotStuffPhase, blockId: BlockId, blockHeight: Height, voterIndex: Int, signature: ByteStr, committeeEpoch: Int = 0`), `HotStuffQuorum.voteMessage(view, phase, blockId, blockHeight, committeeEpoch): Array[Byte]` (public, `HotStuffQuorum.scala:40`), `BlsUtils.verifyBasic(sig: Array[Byte], msg: Array[Byte], pk: Array[Byte]): Either[..., Unit]`.
- Produces: `case class HotStuffEquivocationProof(voteA: HotStuffVote, voteB: HotStuffVote)` with derived `voterIndex: Int`, `view: Int`, `phase: HotStuffPhase`, `committeeEpoch: Int`, and methods `consistent: Either[String, Unit]`, `signaturesValid(blsKeyOf: Int => Option[BlsPublicKey]): Either[String, Unit]`. Later tasks (2, 4, 7, 8) use exactly these names.

- [ ] **Step 1: Bump the schema version**

In `project/Dependencies.scala`, change:

```scala
    "io.decentralchain" % "protobuf-schemas" % "1.6.5" classifier "protobuf-src" intransitive ()
```

to `"1.6.6"`. Run `sbt "node/compile"` — must resolve from `~/.m2` and regenerate scalapb bindings including `PBHotStuffEquivocationProof` (generated class name; check `target` output or just proceed — Task 2's compile proves it).

- [ ] **Step 2: Write the failing test**

```scala
package com.decentralchain.consensus.hotstuff

import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey}
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.Height
import io.decentralchain.protobuf.block.HotStuffPhase
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class HotStuffEquivocationProofSpecification extends AnyFreeSpec with Matchers {

  // Real BLS keypair so signaturesValid is tested for real, not mocked. Follow the construction
  // pattern of an existing spec that signs real votes: grep -l "BlsKeyPair" node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/
  // (HotStuffQuorumSpecification or HotStuffCrossEpochForkSpecification have the seed/keygen helper) and copy it exactly.
  private val kp: BlsKeyPair = ??? // replace with the repo's real test-keypair helper before running

  private def signedVote(voter: Int, view: Int, phase: HotStuffPhase, blockIdByte: Byte, epoch: Int, keyPair: BlsKeyPair): HotStuffVote = {
    val blockId = ByteStr(Array.fill(32)(blockIdByte))
    val height  = Height(10)
    val msg     = HotStuffQuorum.voteMessage(view, phase, blockId, height.toInt, epoch)
    HotStuffVote(view, phase, blockId, height, voter, ByteStr(keyPair.sign(msg).arr), epoch)
  }

  private val prepare = HotStuffPhase.HOTSTUFF_PHASE_PREPARE

  "consistent" - {
    "accepts two votes: same voter/view/phase/epoch, different blockIds" in {
      val p = HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 2, 2, kp))
      p.consistent shouldBe Right(())
      p.voterIndex shouldBe 0; p.view shouldBe 5; p.committeeEpoch shouldBe 2
    }
    "rejects same blockId (not an equivocation)" in {
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 1, 2, kp)).consistent.isLeft shouldBe true
    }
    "rejects different voters" in {
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(1, 5, prepare, 2, 2, kp)).consistent.isLeft shouldBe true
    }
    "rejects different views" in {
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 6, prepare, 2, 2, kp)).consistent.isLeft shouldBe true
    }
    "rejects different phases" in {
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, HotStuffPhase.HOTSTUFF_PHASE_COMMIT, 2, 2, kp)).consistent.isLeft shouldBe true
    }
    "rejects CROSS-EPOCH pairs (same index may be a different generator)" in {
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 2, 3, kp)).consistent.isLeft shouldBe true
    }
    "rejects UNSPECIFIED phase" in {
      val u = HotStuffPhase.HOTSTUFF_PHASE_UNSPECIFIED
      HotStuffEquivocationProof(signedVote(0, 5, u, 1, 2, kp), signedVote(0, 5, u, 2, 2, kp)).consistent.isLeft shouldBe true
    }
  }

  "signaturesValid" - {
    "accepts when both votes verify against the voter's real key" in {
      val p = HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 2, 2, kp))
      p.signaturesValid(_ => Some(kp.publicKey)) shouldBe Right(())
    }
    "rejects a forged voteB (an attacker cannot frame an honest voter)" in {
      val forged = signedVote(0, 5, prepare, 2, 2, kp).copy(signature = ByteStr(Array.fill(96)(7: Byte)))
      HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), forged).signaturesValid(_ => Some(kp.publicKey)).isLeft shouldBe true
    }
    "rejects when the index is outside the committee" in {
      val p = HotStuffEquivocationProof(signedVote(0, 5, prepare, 1, 2, kp), signedVote(0, 5, prepare, 2, 2, kp))
      p.signaturesValid(_ => None).isLeft shouldBe true
    }
  }
}
```

Before running: replace the `???` keypair with the repo's real test BLS-keypair helper (instruction in the comment). If `kp.publicKey`/`kp.sign` names differ, match the helper's actual API.

- [ ] **Step 3: Run test to verify it fails**

Run: `sbt "node-tests/testOnly com.decentralchain.consensus.hotstuff.HotStuffEquivocationProofSpecification"`
Expected: FAIL — `HotStuffEquivocationProof` does not exist (compile error).

- [ ] **Step 4: Write the implementation**

`node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationProof.scala`:

```scala
package com.decentralchain.consensus.hotstuff

import com.decentralchain.crypto.bls.{BlsPublicKey, BlsUtils}
import com.decentralchain.network.HotStuffVote
import io.decentralchain.protobuf.block.HotStuffPhase

/** Verifiable proof that one committee member double-signed within a single (view, phase, epoch):
  * `voteA` and `voteB` are two real, independently BLS-verifiable `HotStuffVote`s for DIFFERENT
  * blockIds. Design SSOT: docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md.
  *
  * DELIBERATELY stores no top-level voter/view/phase fields: everything is derived from `voteA`, so
  * the index that gets slashed can never disagree with the votes the signatures actually cover
  * (review finding C3 — a stored-but-unchecked top-level index would let a real equivocation pair
  * by voter X be wrapped as evidence against innocent voter Y). The wire message's redundant
  * top-level fields are validated against `vote_a` at PB decode (PBHotStuffEquivocationProofs).
  *
  * The epoch-equality rule in `consistent` is load-bearing (finding C2): `committeeEpoch` is inside
  * each vote's signed bytes (T10), but two independently-signed votes CAN carry different epochs,
  * and the same voterIndex under different epochs may be two different physical generators — a
  * cross-epoch pair proves nothing and must never be treated as equivocation.
  */
case class HotStuffEquivocationProof(voteA: HotStuffVote, voteB: HotStuffVote) {
  def voterIndex: Int      = voteA.voterIndex
  def view: Int            = voteA.view
  def phase: HotStuffPhase = voteA.phase
  def committeeEpoch: Int  = voteA.committeeEpoch

  def consistent: Either[String, Unit] = for {
    _ <- Either.cond(voteA.voterIndex == voteB.voterIndex, (), "proof votes name different voters")
    _ <- Either.cond(voteA.view == voteB.view, (), "proof votes are for different views")
    _ <- Either.cond(voteA.phase == voteB.phase, (), "proof votes are for different phases")
    _ <- Either.cond(voteA.phase != HotStuffPhase.HOTSTUFF_PHASE_UNSPECIFIED, (), "proof votes have unspecified phase")
    _ <- Either.cond(voteA.committeeEpoch == voteB.committeeEpoch, (), "proof votes span committee epochs")
    _ <- Either.cond(voteA.blockId != voteB.blockId, (), "proof votes target the same block -- not an equivocation")
  } yield ()

  /** Verify both signatures against the named voter's BLS key, over the SAME canonical bytes real
    * votes sign (`HotStuffQuorum.voteMessage`) — never a reimplementation of the message format.
    */
  def signaturesValid(blsKeyOf: Int => Option[BlsPublicKey]): Either[String, Unit] = for {
    pk <- blsKeyOf(voterIndex).toRight(s"equivocation proof voter index $voterIndex outside committee")
    _  <- verifyOne(voteA, pk, "voteA")
    _  <- verifyOne(voteB, pk, "voteB")
  } yield ()

  private def verifyOne(v: HotStuffVote, pk: BlsPublicKey, label: String): Either[String, Unit] =
    BlsUtils
      .verifyBasic(v.signature.arr, HotStuffQuorum.voteMessage(v.view, v.phase, v.blockId, v.blockHeight.toInt, v.committeeEpoch), pk.arr)
      .left
      .map(e => s"equivocation proof $label signature invalid for voter $voterIndex: $e")

  override def toString: String =
    s"HotStuffEquivocationProof(voter=$voterIndex, v=$view, $phase, epoch=$committeeEpoch, a=${voteA.blockId.trim}, b=${voteB.blockId.trim})"
}
```

Adjust `BlsUtils.verifyBasic`'s exact signature/return to the real one (see its use at `state/appender/package.scala:407` and `HotStuffQuorum.scala:64-66`) — do not guess; open the file.

- [ ] **Step 5: Run test to verify it passes**

Run: `sbt "node-tests/testOnly com.decentralchain.consensus.hotstuff.HotStuffEquivocationProofSpecification"`
Expected: PASS, all cases.

- [ ] **Step 6: Commit**

```bash
git add project/Dependencies.scala node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationProof.scala node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationProofSpecification.scala
git commit -m "feat(hotstuff): HotStuffEquivocationProof — derived fields, epoch-equal rule (T5 rev2)

Schema bumped to 1.6.6. No stored top-level fields (C3), cross-epoch
pairs rejected in consistent (C2), signatures verified over the real
HotStuffQuorum.voteMessage bytes."
```

---

### Task 2: Carry proofs in `FinalizationVoting` — strict PB conversions

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/block/FinalizationVoting.scala`
- Create: `node/src/main/scala/io/decentralchain/protobuf/block/PBHotStuffEquivocationProofs.scala`
- Modify: `node/src/main/scala/io/decentralchain/protobuf/block/PBFinalizationVotings.scala`
- Test: `node/tests/src/test/scala/io/decentralchain/protobuf/block/PBHotStuffEquivocationProofsSpecification.scala`

**Interfaces:**
- Consumes: `HotStuffEquivocationProof` (Task 1).
- Produces: `FinalizationVoting.hotstuffConflicts: Seq[HotStuffEquivocationProof] = Seq.empty`; `FinalizationVoting.nonEmpty` includes it; `FinalizationVoting.combine` concatenates it; `PBHotStuffEquivocationProofs.vanilla(pb): HotStuffEquivocationProof` (throws `IllegalArgumentException` on malformed/mismatched input) and `.protobuf(x): PBHotStuffEquivocationProof`.

- [ ] **Step 1: Write the failing test**

```scala
package io.decentralchain.protobuf.block

import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.HotStuffEquivocationProof
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.Height
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class PBHotStuffEquivocationProofsSpecification extends AnyFreeSpec with Matchers {

  private def vote(voter: Int, view: Int, blockIdByte: Byte, epoch: Int): HotStuffVote =
    HotStuffVote(view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, ByteStr(Array.fill(32)(blockIdByte)), Height(10), voter, ByteStr(Array.fill(96)(1: Byte)), epoch)

  private val proof = HotStuffEquivocationProof(vote(3, 7, 1, 2), vote(3, 7, 2, 2))

  "round-trips a proof, top-level fields derived from voteA" in {
    val pb = PBHotStuffEquivocationProofs.protobuf(proof)
    pb.voterIndex shouldBe 3; pb.view shouldBe 7
    PBHotStuffEquivocationProofs.vanilla(pb) shouldBe proof
  }

  "REJECTS a wire proof whose top-level voter_index disagrees with vote_a (framing attempt)" in {
    val pb = PBHotStuffEquivocationProofs.protobuf(proof).copy(voterIndex = 9)
    an[IllegalArgumentException] should be thrownBy PBHotStuffEquivocationProofs.vanilla(pb)
  }

  "REJECTS a wire proof missing vote_a or vote_b" in {
    val pb = PBHotStuffEquivocationProofs.protobuf(proof)
    an[IllegalArgumentException] should be thrownBy PBHotStuffEquivocationProofs.vanilla(pb.copy(voteA = None))
    an[IllegalArgumentException] should be thrownBy PBHotStuffEquivocationProofs.vanilla(pb.copy(voteB = None))
  }

  "FinalizationVoting round-trip carries hotstuffConflicts, and an empty field decodes to empty (1.6.5 compat)" in {
    // Build a minimal FinalizationVoting the way PBFinalizationVotingsSpecification (if present) or
    // another existing FV test does; assert protobuf->vanilla round-trip preserves hotstuffConflicts,
    // and that a PB FinalizationVoting with hotstuffConflicts = Nil decodes to Seq.empty.
    // Copy the reference branch's test shape: git show 6d98b38394 -- node/tests/src/test/scala/io/decentralchain/protobuf/block/PBFinalizationVotingsSpecification.scala
    val fv   = com.decentralchain.block.FinalizationVoting(Seq.empty, com.decentralchain.state.Height(1), None, Seq.empty, Seq(proof))
    val back = PBFinalizationVotings.vanilla(PBFinalizationVotings.protobuf(fv))
    back.hotstuffConflicts shouldBe Seq(proof)
    fv.nonEmpty shouldBe true
  }
}
```

Check the generated PB class's actual field names (`voteA` vs `vote_a` accessor) against `sbt`-generated sources; scalapb generates camelCase (`voteA`), matching the reference branch's converter.

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "node-tests/testOnly io.decentralchain.protobuf.block.PBHotStuffEquivocationProofsSpecification"`
Expected: FAIL — `PBHotStuffEquivocationProofs` does not exist.

- [ ] **Step 3: Extend `FinalizationVoting`**

In `node/src/main/scala/com/decentralchain/block/FinalizationVoting.scala` (current dev shape — batched `withValid`; do NOT copy the stale reference-branch signature):

```scala
import com.decentralchain.consensus.hotstuff.HotStuffEquivocationProof

case class FinalizationVoting(
    valid: Seq[GeneratorIndex],
    finalizedHeight: Height,
    aggregatedEndorsement: Option[BlsSignature],
    conflict: Seq[BlockEndorsement],
    hotstuffConflicts: Seq[HotStuffEquivocationProof] = Seq.empty
) {
  // withValid: UNCHANGED (keep dev's batched signature verbatim)

  def nonEmpty: Boolean = valid.nonEmpty || conflict.nonEmpty || hotstuffConflicts.nonEmpty

  override def toString: String =
    s"Voting(v=[${valid.mkString(",")}], h=$finalizedHeight, c=[${conflict.mkString(", ")}], " +
      s"hsc=[${hotstuffConflicts.mkString(", ")}], s=$aggregatedEndorsement)"
}
```

And in the companion:

```scala
  def combine(old: FinalizationVoting, recent: FinalizationVoting): FinalizationVoting =
    recent.copy(conflict = old.conflict ++ recent.conflict, hotstuffConflicts = old.hotstuffConflicts ++ recent.hotstuffConflicts)
```

- [ ] **Step 4: Write the strict PB converter**

`node/src/main/scala/io/decentralchain/protobuf/block/PBHotStuffEquivocationProofs.scala`:

```scala
package io.decentralchain.protobuf.block

import com.decentralchain.consensus.hotstuff.HotStuffEquivocationProof
import com.decentralchain.network.HotStuffVote

object PBHotStuffEquivocationProofs {

  /** STRICT decode: throws IllegalArgumentException (failing the whole block parse, exactly like a
    * malformed conflict endorsement in PBFinalizationVotings) on a missing vote or on top-level
    * fields that disagree with vote_a. Proofs are consensus-critical inputs to conflictGenerators —
    * silent drops would both hide a framing attempt (design finding C3) and break decode/re-encode
    * round-trip identity for header bytes. Deterministic: same bytes reject identically everywhere.
    */
  def vanilla(pb: PBHotStuffEquivocationProof): HotStuffEquivocationProof = {
    val pbA   = pb.voteA.getOrElse(throw new IllegalArgumentException("HotStuffEquivocationProof missing vote_a"))
    val pbB   = pb.voteB.getOrElse(throw new IllegalArgumentException("HotStuffEquivocationProof missing vote_b"))
    val proof = HotStuffEquivocationProof(HotStuffVote.fromProtobuf(pbA), HotStuffVote.fromProtobuf(pbB))
    if (pb.voterIndex != proof.voterIndex || pb.view != proof.view || pb.phase != proof.phase)
      throw new IllegalArgumentException(
        s"HotStuffEquivocationProof top-level fields (voter=${pb.voterIndex}, view=${pb.view}, phase=${pb.phase}) " +
          s"disagree with vote_a (voter=${proof.voterIndex}, view=${proof.view}, phase=${proof.phase})"
      )
    proof
  }

  def protobuf(x: HotStuffEquivocationProof): PBHotStuffEquivocationProof = PBHotStuffEquivocationProof.of(
    voterIndex = x.voterIndex,
    view = x.view,
    phase = x.phase,
    voteA = Some(x.voteA.toProtobuf),
    voteB = Some(x.voteB.toProtobuf)
  )
}
```

- [ ] **Step 5: Wire into `PBFinalizationVotings`**

In `vanilla`: add `pb.hotstuffConflicts.map(PBHotStuffEquivocationProofs.vanilla).toVector` as the new constructor argument (NOT `flatMap` — strict, not drop). In `protobuf`: add `v.hotstuffConflicts.map(PBHotStuffEquivocationProofs.protobuf)`. Reference for placement: `git show 6d98b38394 -- node/src/main/scala/io/decentralchain/protobuf/block/PBFinalizationVotings.scala` (but replace its `flatMap`-drop with the strict map).

- [ ] **Step 6: Run tests, full FV/finalization suites**

Run: `sbt "node/compile" "node-tests/testOnly io.decentralchain.protobuf.block.* com.decentralchain.finalization.*"`
Expected: new spec PASS; existing FV round-trip and finalization suites unaffected (old call sites compile via the default arg).

- [ ] **Step 7: Commit**

```bash
git add -A node/src/main/scala node/tests/src
git commit -m "feat(hotstuff): carry HotStuffEquivocationProof in FinalizationVoting (strict decode)

hotstuffConflicts rides the FV like T0's conflict does (combine
concatenates both). PB decode is strict: missing votes or top-level
fields disagreeing with vote_a fail block parsing -- proofs are
consensus-critical, silent drops would hide framing and break header
round-trip identity."
```

---

### Task 3: Feature 29 + `supportsHotStuffEquivocationEvidence`

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala`
- Modify: `node/src/main/scala/com/decentralchain/state/Blockchain.scala` (next to `supportsFinalizationVoting`, `:309`)
- Test: extend whichever spec covers feature registration (grep `"DeterministicFinality"` under `node/tests/src` for the pattern; if none asserts registry contents, a 2-line check in the Task 4 validation spec suffices)

**Interfaces:**
- Produces: `BlockchainFeatures.HotStuffEquivocationEvidence` (id 29, in `dict` ⇒ votable/implemented); `blockchain.supportsHotStuffEquivocationEvidence(height: Int = blockchain.height): Boolean`.

- [ ] **Step 1: Add the feature**

In `BlockchainFeature.scala` after `DeterministicFinality`:

```scala
  val HotStuffEquivocationEvidence    = BlockchainFeature(29, "HotStuff Equivocation Evidence")
```

Add `HotStuffEquivocationEvidence` to the `dict` Seq. (Ids 26/27 are taken by not-exposed features. Id 28 is BURNED — it was ModernGroth16Verifier, deleted in 62f8a1240a, and testnet configs historically pre-activated it (stale-config incident 2026-08-30); reusing it invites mixed-semantics confusion. Id 29 verified free on dev AND main, and absent from all infra configs.)

- [ ] **Step 2: Add the support helper**

In `Blockchain.scala`, directly below `supportsFinalizationVoting` (`:309-310`), same shape:

```scala
    def supportsHotStuffEquivocationEvidence(height: Int = blockchain.height): Boolean =
      blockchain.featureActivationHeight(BlockchainFeatures.HotStuffEquivocationEvidence).exists(Height(height) >= _)
```

- [ ] **Step 3: Compile + run feature/settings suites**

Run: `sbt "node/compile" "node-tests/testOnly com.decentralchain.features.* com.decentralchain.settings.*"`
Expected: green. (Some feature specs assert the implemented-set size — if one fails on the new entry, update its expected count/contents; that IS the test of this task.)

- [ ] **Step 4: Commit**

```bash
git add -A node/src node/tests/src
git commit -m "feat(consensus): feature 29 HotStuff Equivocation Evidence + support helper

On-chain activation gate for hotstuffConflicts (design finding H4):
proto3 compat is wire-level only -- an evidence-unaware node ignoring
the field would compute different conflictGenerators from the same
block bytes. Activation voting is the codebase's standard fix."
```

---

### Task 4: Deterministic proof validation in `validateFinalizationVoting`

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/appender/package.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/state/appender/HotStuffEquivocationValidationSpecification.scala`

**Interfaces:**
- Consumes: `HotStuffEquivocationProof.consistent/signaturesValid` (Task 1), `blockchain.supportsHotStuffEquivocationEvidence` (Task 3), and `validateFinalizationVoting`'s existing locals: `blockGenerationPeriod` (`:367-369`), `allCommittedGenerators: IndexedSeq[(Address, BlsPublicKey)]` (`:370`), `knownConflictGenerators: Set[GeneratorIndex]` (`:381`).
- Produces: private `validateHotStuffEquivocationProofs(...): Either[String, Unit]` wired into `validateFinalizationVoting`; the function's returned `nonConflictingGenerators` also excludes proof voters; the emptiness check counts `hotstuffConflicts`.

- [ ] **Step 1: Write the failing test**

Test through the public `validateFinalizationVoting(block, blockchain, generatorSet)`. Build the blockchain stub the way this package's existing appender/finalization specs do — find the pattern: `grep -rln "validateFinalizationVoting" node/tests/src/test/scala/` and copy that spec's fixture (committee with real BLS keys, `committedGenerators`, `generationPeriodOf`, `conflictGenerators`, `featureActivationHeight` stubs). Cases (each a block whose header FV differs only in `hotstuffConflicts`):

```text
1. valid proof (same epoch as block period, real signatures, fresh voter)      => Right, and result excludes the voter
2. hotstuffConflicts non-empty BEFORE feature-29 activation                     => Left("...not allowed before HotStuff Equivocation Evidence activation...")
3. proofs-only FV (valid=[], conflict=[], one valid proof), post-activation     => Right   [C4: emptiness check relaxed]
4. proof whose committeeEpoch != block period index                             => Left (epoch/period mismatch)
5. cross-epoch vote pair (voteA.epoch != voteB.epoch)                           => Left (consistent fails)
6. forged voteB signature                                                       => Left (signaturesValid fails)
7. voter index out of committee bounds                                          => Left
8. duplicate voter across two proofs in one FV                                  => Left
9. voter already in knownConflictGenerators                                     => Left ("already excluded")
10. voter also present in fv.conflict's endorser indexes                        => Left (one exclusion per voter per block)
11. proof from the MINER's own index                                            => Right (an equivocating leader IS slashable — unlike T0's miner rule)
```

Write all 11 as concrete test cases with real signed votes (reuse Task 1's `signedVote` helper — factor it into a shared test util if the fixture file grows).

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "node-tests/testOnly com.decentralchain.state.appender.HotStuffEquivocationValidationSpecification"`
Expected: FAIL — cases 1/3/11 fail because validation doesn't exist yet and the emptiness check rejects case 3; cases 2/4-10 fail because nothing rejects them.

- [ ] **Step 3: Relax the emptiness check** (`appender/package.scala:360`)

```scala
          _ <- Either.raiseWhen(fv.valid.isEmpty && fv.conflict.isEmpty && fv.hotstuffConflicts.isEmpty)(
            "Finalization voting contains neither valid nor conflicting endorsements nor equivocation proofs"
          )
```

- [ ] **Step 4: Add the validator + wiring**

New private function in the same file, structurally beside `validateConflictingEndorsement`:

```scala
  private def validateHotStuffEquivocationProofs(
      blockchain: Blockchain,
      fv: FinalizationVoting,
      blockGenerationPeriodIndex: Int,
      commitedGenerators: IndexedSeq[(Address, BlsPublicKey)],
      knownConflictGenerators: Set[GeneratorIndex],
      blockHeight: Int
  ): Either[String, Unit] =
    if (fv.hotstuffConflicts.isEmpty) Right(())
    else
      for {
        _ <- Either.raiseUnless(blockchain.supportsHotStuffEquivocationEvidence(blockHeight))(
          "HotStuff equivocation evidence is not allowed before HotStuff Equivocation Evidence feature activation"
        )
        voters = fv.hotstuffConflicts.map(_.voterIndex)
        _ <- Either.raiseWhen(voters.toSet.size != voters.length)("Duplicate equivocation-proof voter indexes")
        conflictIdxs = fv.conflict.map(_.endorserIndex.toInt).toSet
        _ <- fv.hotstuffConflicts.toList.traverse { proof =>
          for {
            _ <- proof.consistent
            _ <- Either.raiseUnless(proof.committeeEpoch == blockGenerationPeriodIndex)(
              s"Equivocation proof epoch ${proof.committeeEpoch} does not match block generation period $blockGenerationPeriodIndex"
            )
            _ <- Either.raiseUnless(proof.voterIndex >= 0 && proof.voterIndex < commitedGenerators.length)(
              s"Equivocation proof voter index ${proof.voterIndex} outside committee (size ${commitedGenerators.length})"
            )
            _ <- Either.raiseWhen(knownConflictGenerators.contains(GeneratorIndex(proof.voterIndex)))(
              s"Voter ${proof.voterIndex} is already excluded as a conflict generator"
            )
            _ <- Either.raiseWhen(conflictIdxs.contains(proof.voterIndex))(
              s"Voter ${proof.voterIndex} already carries a conflicting endorsement in this voting"
            )
            _ <- proof.signaturesValid(i => commitedGenerators.lift(i).map(_._2))
          } yield ()
        }
      } yield ()
```

Wire it inside `validateFinalizationVoting`'s for-comprehension, immediately after the existing `fv.conflict.traverse(validateConflictingEndorsement(...))` block (`:394`), reusing the locals already in scope (`blockGenerationPeriod.index`, `allCommittedGenerators`, `knownConflictGenerators`, `blockHeight.toInt`). Then extend the exclusion of the returned set (`:395-396`):

```scala
          conflictingEndorsers     = fv.conflict.map(_.endorserIndex).toSet ++ fv.hotstuffConflicts.map(p => GeneratorIndex(p.voterIndex)).toSet
          nonConflictingGenerators = generatorSet.filterNot(x => conflictingEndorsers.contains(x.index))
```

(`GeneratorIndex(int)` construction is the codebase's established pattern — `Blockchain.scala:305`.)

**Do NOT reference `hotStuffSettings` anywhere in this file** — validation is unconditional (Global Constraints / spec §5).

- [ ] **Step 5: Run the new spec + full appender/finalization suites**

Run: `sbt "node-tests/testOnly com.decentralchain.state.appender.* com.decentralchain.finalization.* com.decentralchain.state.*"`
Expected: 11/11 new cases PASS; zero regressions.

- [ ] **Step 6: Commit**

```bash
git add -A node/src node/tests/src
git commit -m "feat(consensus): validate HotStuff equivocation proofs at block ingress

All-or-nothing, unconditional (no local-flag gating -- determinism
contract, finding H5), feature-29 gated on-chain. Rules: internal
consistency incl. epoch equality (C2), epoch == block generation
period (index-space pin, C2), bounds, dedup + already-excluded +
conflict-overlap (M3), both BLS signatures over the real voteMessage
bytes. Proofs-only FinalizationVoting is now legal (C4)."
```

---

### Task 5: Union into `conflictGenerators` — BOTH layers [C1]

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/FinalizationState.scala:23`
- Modify: `node/src/main/scala/com/decentralchain/database/Caches.scala:409-412`
- Test: `node/tests/src/test/scala/com/decentralchain/state/FinalizationStateHotStuffConflictsSpecification.scala` + extend the existing Caches/RocksDB persistence spec (grep `"conflictGenerators"` under `node/tests/src` for where T0's persistence is already asserted; add the sibling case there)

**Interfaces:**
- Consumes: `FinalizationVoting.hotstuffConflicts` (Task 2). No signature changes anywhere — the proofs ride inside the FV both functions already receive.
- Produces: verified proof voters participate in `isParentFinalized`'s stake denominator, the liquid accumulated set, AND the persisted period-keyed store (`Keys.conflictGenerators`) that `FinalizationState.init` rebuilds from.

- [ ] **Step 1: Write the failing tests**

`FinalizationStateHotStuffConflictsSpecification`: construct a `FinalizationState` with a small `generatorSet` (reuse the construction pattern of the existing `FinalizationState` spec — grep `"FinalizationState"` under `node/tests/src`), call `.append(blockId, Some(fvWithOneProof), generatorSet)` where `fvWithOneProof.hotstuffConflicts = Seq(proof for voter 2)`, and assert:

```text
1. updatedState.conflictGenerators contains GeneratorIndex(2)
2. voter 2's balance is EXCLUDED from isParentFinalized's totals
   (assert via the parentFinalized outcome flipping on a boundary-quorum fixture,
    same technique the existing conflict tests use)
3. composition: fv with BOTH a T0 conflict (voter 1) and a hotstuff proof (voter 2)
   yields conflictGenerators ⊇ {1, 2}
```

Caches/persistence case: in the existing persistence spec, append a key block whose header FV carries one hotstuff proof (no T0 conflict) and assert `blockchain.conflictGenerators(period).upTo(h)` contains the voter after the append — i.e. the exclusion SURVIVES the key-block boundary (this is the regression test for review finding C1).

- [ ] **Step 2: Run tests to verify they fail**

Expected: fail — voter absent from `conflictGenerators` in all cases.

- [ ] **Step 3: Extend both derivations**

`FinalizationState.scala:23`:

```scala
    val newConflictGenerators =
      newFinalizationVoting.fold(Set.empty[GeneratorIndex]) { fv =>
        fv.conflict.view.map(_.endorserIndex).toSet ++ fv.hotstuffConflicts.view.map(p => GeneratorIndex(p.voterIndex))
      }
```

`Caches.scala:409-412`:

```scala
    val conflictGenerators = for {
      v <- block.header.finalizationVoting.toSeq
      idx <- v.conflict.map(_.endorserIndex) ++ v.hotstuffConflicts.map(p => GeneratorIndex(p.voterIndex))
    } yield idx
```

Nothing else changes — that single value already flows to `conflictGeneratorsCache` (`:427-429`), `doAppend`'s persistence (`RocksDBWriter.scala:753`), rollback deletion (`:1163`), and the deposit-punishment computation (`:1493`). State in the commit message that deposit forfeiture is intentional.

- [ ] **Step 4: Run tests + full state/database suites**

Run: `sbt "node-tests/testOnly com.decentralchain.state.* com.decentralchain.database.* com.decentralchain.finalization.*"`
Expected: new cases PASS; zero regressions (blocks without proofs produce byte-identical behavior — the union of an empty seq).

- [ ] **Step 5: Commit**

```bash
git add -A node/src node/tests/src
git commit -m "feat(consensus): union verified equivocation proofs into conflictGenerators -- both layers

Liquid (FinalizationState.append) AND persisted (Caches.doAppend
extraction -> Keys.conflictGenerators). The persisted layer is the one
rev.1 missed (finding C1): without it the exclusion evaporated at the
next key block. Period-scoped, rollback-deleted, and deliberately
carries T0's existing deposit-forfeiture consequence -- that is T5's
point."
```

---

### Task 6: `dcc.hotstuff.slashing-enabled` setting

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/settings/HotStuffSettings.scala`
- Modify: `node/src/main/resources/application.conf` (the `dcc.hotstuff` block)
- Test: extend `node/tests/src/test/scala/com/decentralchain/settings/HotStuffSettingsSpecification.scala`

**Interfaces:**
- Produces: `HotStuffSettings.slashingEnabled: Boolean = false`, `require(!slashingEnabled || enabled)`.

- [ ] **Step 1: Failing test** — in the existing settings spec, add: default parse yields `slashingEnabled = false`; `slashing-enabled = true` with `enabled = true` parses; `slashing-enabled = true` with `enabled = false` throws.
- [ ] **Step 2: Run to verify it fails** (field doesn't exist).
- [ ] **Step 3: Implement** — add the field + require, mirroring `authoritative`'s placement. Doc-comment (replaces the stale reference-branch text):

```scala
  * @param slashingEnabled T5 rev.2 (docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md):
  *                     gates ONLY whether THIS node's miner folds pending equivocation proofs into
  *                     blocks it forges. Proof VALIDATION and the conflictGenerators union are
  *                     unconditional (gated solely by feature-29 activation) -- a node with this
  *                     flag off applies exclusions from received proof-carrying blocks identically,
  *                     so mixed flag settings can never diverge consensus. TESTNET-ONLY until
  *                     externally audited; consequences are real (generation-deposit forfeiture).
  *                     OPERATIONAL NOTE: a replica's very first boot has no persisted lastVotedView
  *                     (T11 first-boot window) -- do not run a first-boot replica as a committee
  *                     member with slashing active until it has participated once.
```

`application.conf`: `slashing-enabled = no` with a matching comment.
- [ ] **Step 4: Run settings suite** — green.
- [ ] **Step 5: Commit** (`feat(hotstuff): slashing-enabled flag -- gates evidence production only`).

---

### Task 7: Coordinator detection, verified-evidence retention, `onEquivocation` + metric

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffCoordinator.scala`
- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/NodeHotStuffEffects.scala`
- Create: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationObservation.scala`
- Modify: `node/src/main/scala/com/decentralchain/api/http/NodeApiRoute.scala:36-44`
- Test: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationDetectionSpecification.scala`

**Interfaces:**
- Consumes: `HotStuffEquivocationProof` (Task 1); `pool.pending: Map[(Int, HotStuffPhase, BlockId), Vector[HotStuffVote]]`; `HotStuffSafety.equivocators`.
- Produces:
  - `HotStuffCoordinator.detectedEquivocations: Seq[HotStuffEquivocationProof]` (trait method; `Disabled` returns `Seq.empty`) — Task 8's miner reads this.
  - `HotStuffCoordinator.pruneEquivocations(alreadyExcluded: Int => Boolean, currentPeriodIndex: Int): Unit` — retention rule [M2]: drop proofs whose voter is already excluded on-chain or whose `committeeEpoch < currentPeriodIndex`.
  - `HotStuffEffects.onEquivocation(proof: HotStuffEquivocationProof): Unit` (every implementer updated; production = ERROR log + observation bump).
  - `HotStuffEquivocationObservation.recordEquivocation(): Unit` / `.totalCount: Int` (AtomicInteger, mirrors `HotStuffObservation`); `/node/status` gains `hotStuffEquivocationsTotal` (present only when > 0), which finally feeds the ALREADY-DEPLOYED infra metric `dcc_hotstuff_equivocations_total` + critical alert.

- [ ] **Step 1: Write the failing test**

Construct `HotStuffCoordinator.Enabled` with a `RecordingEffects` fake — copy the exact construction pattern from `HotStuffWatchdogRejectedStreamSpecification` or `HotStuffSimulationSpecification` (they construct `Enabled` directly against current dev; do NOT guess parameters). Committee: 2+ real BLS keypairs so votes verify. Cases:

```text
1. two verified conflicting votes from voter 0 at same (view, phase, epoch)
   -> detectedEquivocations has exactly ONE proof (voter 0), effects.onEquivocation called ONCE
2. re-delivering either vote -> still one proof, no second onEquivocation (dedup by (voter, view, phase))
3. two votes same voter/view/phase but DIFFERENT committeeEpoch -> NO proof (consistent rejects)
4. one real vote + one FORGED-signature conflicting vote -> NO proof (cannot frame via a forgery)
5. pruneEquivocations(alreadyExcluded = _ == 0, currentPeriodIndex = anything) -> proofs for voter 0 removed
6. pruneEquivocations(_ => false, currentPeriodIndex = proofEpoch + 1) -> stale-epoch proof removed
7. detection fires regardless of slashingEnabled (construct without any settings dependency --
   detection is a coordinator behavior, the flag lives in the miner)
```

- [ ] **Step 2: Run to verify it fails** (no `detectedEquivocations` member, no `onEquivocation`).

- [ ] **Step 3: Implement detection in `onVote`**

Insert after `pool = nextPool` (`HotStuffCoordinator.scala:309`), following the reference branch's bucket-gathering insight (`git show 456b7058ad -- .../HotStuffCoordinator.scala`) upgraded to rev.2 rules:

```scala
      // T5 rev.2: pool.pending is keyed by the FULL (view, phase, blockId) target, so a double-signer's
      // votes land in different buckets -- gather every bucket sharing (view, phase) before running
      // HotStuffSafety.equivocators. Only a proof that passes `consistent` (epoch-equal, C2) AND both
      // signature checks is recorded: a forged vote can never frame an honest voter, and a cross-epoch
      // pair is not evidence. Detection is unconditional (observability); slashing-enabled only gates
      // whether the MINER folds these into a block (see Miner.foldHotStuffConflicts).
      val sameRoundVotes = nextPool.pending.collect { case ((v, p, _), vs) if v == vote.view && p == vote.phase => vs }.flatten
      HotStuffSafety.equivocators(sameRoundVotes).foreach { idx =>
        val alreadyRecorded = _detectedEquivocations.exists(e => e.voterIndex == idx && e.view == vote.view && e.phase == vote.phase)
        if (!alreadyRecorded) {
          val byBlock = sameRoundVotes.filter(_.voterIndex == idx).groupBy(_.blockId).values.map(_.head).toSeq
          byBlock match {
            case Seq(a, b, _*) =>
              val proof = HotStuffEquivocationProof(a, b)
              val ok = for {
                _ <- proof.consistent
                _ <- proof.signaturesValid(i => engine.committee.lift(i).map(_.blsPublicKey))
              } yield ()
              ok match {
                case Right(()) =>
                  _detectedEquivocations = _detectedEquivocations :+ proof
                  effects.onEquivocation(proof)
                case Left(reason) =>
                  logger.debug(s"[HotStuff] equivocation candidate for voter #$idx rejected: $reason")
              }
            case _ => ()
          }
        }
      }
```

Plus the state + trait members:

```scala
    private var _detectedEquivocations: Vector[HotStuffEquivocationProof] = Vector.empty
    def detectedEquivocations: Seq[HotStuffEquivocationProof]             = _detectedEquivocations

    def pruneEquivocations(alreadyExcluded: Int => Boolean, currentPeriodIndex: Int): Unit =
      _detectedEquivocations = _detectedEquivocations.filterNot(p => alreadyExcluded(p.voterIndex) || p.committeeEpoch < currentPeriodIndex)
```

Trait: `def detectedEquivocations: Seq[HotStuffEquivocationProof]` and `def pruneEquivocations(alreadyExcluded: Int => Boolean, currentPeriodIndex: Int): Unit` with `Disabled` returning `Seq.empty` / no-op. Check `engine.committee`'s element type for the `.blsPublicKey` accessor (it is a `GeneratorSet` = `Seq[GeneratorInfo]`; `GeneratorInfo` has `blsPublicKey` — see `Blockchain.scala:305`); adapt if the accessor differs.

- [ ] **Step 4: `HotStuffEffects.onEquivocation` + production impl + observation + status field**

Trait method (in `HotStuffCoordinator.scala` where `HotStuffEffects` lives):

```scala
  /** A verified equivocation proof was recorded (at most once per (voter, view, phase)). */
  def onEquivocation(proof: HotStuffEquivocationProof): Unit
```

Update every implementer: `grep -rln "HotStuffEffects" node/src node/tests/src node-it/src` — production gets the real body; test fakes get a recorder or no-op matching each file's existing style.

`HotStuffEquivocationObservation.scala` — mirror `HotStuffObservation`'s AtomicInteger pattern exactly (`recordEquivocation(): Unit`, `totalCount: Int`).

`NodeHotStuffEffects`:

```scala
  override def onEquivocation(proof: HotStuffEquivocationProof): Unit = {
    logger.error(
      s"[HotStuff] EQUIVOCATION DETECTED: voter #${proof.voterIndex} double-signed view=${proof.view} ${proof.phase} " +
        s"epoch=${proof.committeeEpoch} blocks=${proof.voteA.blockId.trim}/${proof.voteB.blockId.trim} -- " +
        s"Byzantine actor or protocol-violating bug; investigate immediately"
    )
    HotStuffEquivocationObservation.recordEquivocation()
  }
```

`NodeApiRoute.scala` (`:36-44`): alongside the existing `hotStuff` object, add

```scala
    val hotStuffEquivocations =
      if (HotStuffEquivocationObservation.totalCount > 0) Json.obj("hotStuffEquivocationsTotal" -> HotStuffEquivocationObservation.totalCount)
      else Json.obj()
```

and append `++ hotStuffEquivocations` to the same merge (`:44`). `/node/status` stays byte-identical when zero.

- [ ] **Step 5: Run the new spec + the full HotStuff suite**

Run: `sbt "node/compile" "node-tests/testOnly com.decentralchain.consensus.hotstuff.*"`
Expected: 7/7 new cases; all existing HotStuff specs green (compiler will name any effects-implementer missed in Step 4).

- [ ] **Step 6: Commit**

```bash
git add -A node/src node/tests/src node-it/src
git commit -m "feat(hotstuff): detect + retain verified equivocation proofs; ERROR log + metric (F-3/T5)

Detection over same-(view,phase) pool buckets; only consistency- and
signature-verified proofs are kept (no framing via forgery, no
cross-epoch pairs). Retention until on-chain or epoch-expired replaces
rev.1's drain-and-lose (M2). /node/status hotStuffEquivocationsTotal
finally feeds the already-deployed dcc_hotstuff_equivocations_total
alert. Closes audit F-3 (equivocators was dead code)."
```

---

### Task 8: Miner fold at key-block forge + `Application` wiring

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/mining/Miner.scala` (fold at `:275`, pure helper in companion)
- Modify: `node/src/main/scala/com/decentralchain/Application.scala` (provider cell + prune wiring)
- Test: `node/tests/src/test/scala/com/decentralchain/mining/MinerHotStuffConflictsSpecification.scala`

**Interfaces:**
- Consumes: `HotStuffCoordinator.detectedEquivocations` / `pruneEquivocations` (Task 7), `HotStuffSettings.slashingEnabled` (Task 6), `blockchain.conflictGenerators(period)`, `blockchain.generationPeriodOf(height)`.
- Produces: `Miner.foldHotStuffConflicts(slashingEnabled, pending, voting, forgeHeightPeriodIndex, alreadyExcluded, fallbackFinalizedHeight): Option[FinalizationVoting]` (pure, unit-tested directly); `MinerImpl` constructor gains `hotStuffEquivocations: () => Seq[HotStuffEquivocationProof] = () => Seq.empty`.

- [ ] **Step 1: Write the failing test** (pure function; no miner fixture needed)

```text
1. slashingEnabled=false, pending nonempty, voting=Some(fv)      => fv UNCHANGED
2. enabled, pending empty                                        => voting unchanged (None stays None)
3. enabled, one proof matching period, voting=Some(fv)           => fv.hotstuffConflicts == Seq(proof)
4. enabled, proof epoch != forge-height period index             => filtered out
5. enabled, proof voter alreadyExcluded                          => filtered out
6. enabled, two proofs same voter                                => deduped to one (keep first)
7. enabled, one valid proof, voting=None                         => Some(FV(valid=[], conflict=[], hotstuffConflicts=[proof],
                                                                    finalizedHeight = fallbackFinalizedHeight(), aggregatedEndorsement=None))
```

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Implement the pure fold** in `object Miner` (reference: `git show 62184efa67 -- .../Miner.scala`, extended with rev.2's filters):

```scala
  /** T5 rev.2: fold pending verified equivocation proofs into the key block's FinalizationVoting.
    * PRODUCTION-side gate only (spec §5): validation/union on receipt are unconditional elsewhere.
    * Filters: epoch must equal the forge height's generation-period index (validation would reject
    * anything else -- rule 3), voter not already excluded on-chain (validation rule 5), dedup by voter.
    * `fallbackFinalizedHeight` is evaluated only when synthesizing an FV from nothing.
    */
  private[mining] def foldHotStuffConflicts(
      slashingEnabled: Boolean,
      pending: Seq[HotStuffEquivocationProof],
      voting: Option[FinalizationVoting],
      forgeHeightPeriodIndex: Int,
      alreadyExcluded: Int => Boolean,
      fallbackFinalizedHeight: () => Height
  ): Option[FinalizationVoting] = {
    val usable =
      if (!slashingEnabled) Seq.empty
      else
        pending
          .filter(p => p.committeeEpoch == forgeHeightPeriodIndex && !alreadyExcluded(p.voterIndex))
          .distinctBy(_.voterIndex)
    if (usable.isEmpty) voting
    else
      voting match {
        case Some(fv) => Some(fv.copy(hotstuffConflicts = (fv.hotstuffConflicts ++ usable).distinctBy(_.voterIndex)))
        case None     => Some(FinalizationVoting(Seq.empty, fallbackFinalizedHeight(), None, Seq.empty, usable))
      }
  }
```

- [ ] **Step 4: Wire into `MinerImpl.forgeBlock`** — change `:275`:

```scala
            finalizationVoting = withHotStuffConflicts(tryCollectSelfWithGrace(reference)),
```

with the instance wrapper (compute the forge height's period index and the exclusion lookup from `blockchainUpdater`; open the surrounding method for the exact height-in-scope variable — it is the parent height + 1):

```scala
  private def withHotStuffConflicts(voting: Option[FinalizationVoting]): Option[FinalizationVoting] = {
    val forgeHeight = Height(blockchainUpdater.height + 1)
    blockchainUpdater.generationPeriodOf(forgeHeight) match {
      case None         => voting // pre-activation: no periods, no committee, nothing to fold
      case Some(period) =>
        Miner.foldHotStuffConflicts(
          settings.hotStuffSettings.slashingEnabled,
          hotStuffEquivocations(),
          voting,
          period.index,
          idx => blockchainUpdater.conflictGenerators(period).upTo(forgeHeight).contains(GeneratorIndex(idx)),
          () => blockchainUpdater.finalizedHeight.getOrElse(GenesisBlockHeight)
        )
    }
  }
```

Constructor param on `MinerImpl`: `hotStuffEquivocations: () => Seq[HotStuffEquivocationProof] = () => Seq.empty` (defaulted — every existing call site compiles unchanged). Verify the exact names `blockchainUpdater.finalizedHeight` / `conflictGenerators` / `generationPeriodOf` against the `Blockchain` trait before compiling; adapt accessors, not semantics.

- [ ] **Step 5: Wire `Application.scala`** (reference: `git show 62184efa67 -- .../Application.scala`):

- `@volatile private var hotStuffEquivocations: () => Seq[HotStuffEquivocationProof] = () => Seq.empty` near the `miner` var (`:102` area).
- Pass `hotStuffEquivocations = () => hotStuffEquivocations()` at the `new MinerImpl(` site (`:163`).
- Inside the `hotstuff.enabled` block, after the coordinator is constructed (near `:351`): `hotStuffEquivocations = () => hsCoordinator.detectedEquivocations`.
- Retention pruning [M2]: on each key-block event the coordinator already observes (find where `refreshCommittee`/new-block subscription drives the coordinator in `Application.scala` — the block-appended subscription that already exists for HotStuff), add:

```scala
        hsCoordinator.pruneEquivocations(
          idx => blockchainUpdater.currentGenerationPeriod.exists(p => blockchainUpdater.conflictGenerators(p).upTo(Height(blockchainUpdater.height)).contains(GeneratorIndex(idx))),
          blockchainUpdater.currentGenerationPeriod.fold(0)(_.index)
        )
```

- [ ] **Step 6: Run** `sbt "node/compile" "node-tests/testOnly com.decentralchain.mining.* com.decentralchain.consensus.hotstuff.*"` — green.

- [ ] **Step 7: Commit** (`feat(hotstuff): miner folds pending equivocation proofs into key blocks (production-gated)`; body notes: key-block fold supersedes rev.1's EndorsementStorage drain (C4), retention means orphaned evidence is re-offered (M2)).

---

### Task 9: Persist `lastVotedView` [M1]

**Files:**
- Create: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffLastVotedViewStore.scala`
- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffCoordinator.scala` (`Enabled` params + persist hook)
- Modify: `node/src/main/scala/com/decentralchain/Application.scala` (wiring beside `HotStuffLockedQCStore`, `:325-379`)
- Test: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffLastVotedViewStoreSpecification.scala`

**Interfaces:**
- Produces: `HotStuffLastVotedViewStore.load(path: Path): Option[Int]`, `.save(path: Path, view: Int): Unit` (same atomic-write/never-throw contract as `HotStuffLockedQCStore`); `Enabled` gains `initialLastVotedView: Int = -1` and `onLastVotedViewPersist: Int => Unit = _ => ()`.

- [ ] **Step 1: Failing test** — round-trip (save 42, load Some(42)); load of a missing file = None; load of a corrupt file (write garbage bytes) = None, no throw. Mirror `HotStuffLockedQCStore`'s existing spec if one exists (grep it) — else these three cases.
- [ ] **Step 2: Run to verify it fails.**
- [ ] **Step 3: Implement the store** — clone `HotStuffLockedQCStore.scala`'s structure verbatim (atomic temp+move, log-and-continue), payload = `view.toString.getBytes(UTF_8)` / `new String(bytes, UTF_8).trim.toInt`. Scaladoc must state WHY (spec §10): a restarted replica with `lastVotedView = -1` can honestly re-sign a conflicting vote in a view it already voted — an alert while detection-only, deposit forfeiture once slashing exists; this store closes the restart window (T11 first-boot remains, documented in `HotStuffSettings.slashingEnabled`'s doc).
- [ ] **Step 4: Coordinator wiring** — `Enabled` params `initialLastVotedView: Int = -1`, `onLastVotedViewPersist: Int => Unit = _ => ()`; construction becomes `SafetyState(lockedQC = initialLockedQC, lastVotedView = initialLastVotedView)` (`:214`); in `onProposal`, after `engine` is reassigned (`:299` area), if `engine.safety.lastVotedView` advanced past its pre-call value, call `onLastVotedViewPersist(engine.safety.lastVotedView)` — exactly the pattern the `onLockedQCPersist` hook uses at `:362`. Also update `resetLocalSafetyState`'s doc: the preserved `lastVotedView` is now also persisted.
- [ ] **Step 5: Application wiring** — beside the `HotStuffLockedQCStore` block (`:325-331`, `:379`): derive `hsLastVotedViewPath` as a sibling file of `hsLockedQCPath` (e.g. `resolveSibling("last-voted-view.dat")`), pass `initialLastVotedView = HotStuffLastVotedViewStore.load(hsLastVotedViewPath).getOrElse(-1)` and `onLastVotedViewPersist = v => HotStuffLastVotedViewStore.save(hsLastVotedViewPath, v)`.
- [ ] **Step 6: Coordinator-level test** — add to `HotStuffResetDoubleVoteSpecification` (the F-2 spec — it already builds the double-vote scenario): a coordinator constructed with `initialLastVotedView = v` refuses to vote at view `v` (i.e. the restart-replay case its RESIDUAL GAP comment documents is now closed when a persisted value exists).
- [ ] **Step 7: Run** `sbt "node-tests/testOnly com.decentralchain.consensus.hotstuff.*"` — green.
- [ ] **Step 8: Commit** (`fix(hotstuff): persist lastVotedView across restarts (M1) -- honest replicas must not become slashable via their own reboot`).

---

### Task 10: End-to-end determinism test + docs closure

**Files:**
- Test: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationEvidenceE2ESpecification.scala`
- Modify: `docs/hotstuff-audit-readiness.md` (T5 row + §7 item 3 + §8 checklist)
- Modify: `docs/superpowers/plans/2026-08-22-hotstuff-equivocation-slashing.md` and `docs/superpowers/plans/2026-09-01-hotstuff-equivocation-detection.md` (supersession banners)

- [ ] **Step 1: Write the E2E test** — the deliverable Critical #2 demands: two independent verifier paths converge from the same bytes.

```text
Fixture: committee of 3 real BLS keypairs; voter 2 double-signs (two verified votes, same
view/phase/epoch, different blockIds).

1. DETECTING node: coordinator ingests both votes -> detectedEquivocations yields proof P.
2. MINER path: foldHotStuffConflicts(slashingEnabled = true, Seq(P), None, periodIndex, _ => false, ...)
   -> Some(fv); serialize fv via PBFinalizationVotings.protobuf, then DESERIALIZE the bytes.
3. RECEIVING node (never saw the votes): run validateFinalizationVoting over a block carrying the
   deserialized fv (feature 29 active in the stub) -> Right, and the returned generatorSet excludes
   voter 2.
4. Both a "detecting" FinalizationState.append and a "receiving" FinalizationState.append (same fv)
   produce IDENTICAL conflictGenerators sets.
5. Negative: flip one byte of voteB's signature in the serialized proof -> receiving node's
   validateFinalizationVoting rejects the whole block.
```

- [ ] **Step 2: Run it** — green.
- [ ] **Step 3: Docs.** `hotstuff-audit-readiness.md`: T5 row → "proof-carried, block-validated exclusion wired (feature 29 + `slashing-enabled`, default off); live testnet exercise pending"; §8 checklist: T5 item checked with the same caveat. Supersession banner on both old plans: superseded by this plan + spec rev.2, with one line on why (branch had no validation/union; F-3 Tasks 1-2 replaced by coordinator-side detection).
- [ ] **Step 4: Full gate** — `sbt "node/compile" "node-tests/test"` (full unit suite) + scalafmt/scalafix per repo convention.
- [ ] **Step 5: Commit** (`test(hotstuff): E2E equivocation-evidence determinism proof + docs closure (T5)`).

---

## Self-Review

- **Spec coverage:** §0→Task 0, §1-2→Task 1, §3→Task 2, §4→Task 3, §5→Task 6 (+ constraints in 4/5), §6→Task 7, §7→Task 8, §8→Task 4, §9→Task 5, §10→Task 9, E2E/docs→Task 10. All eleven review findings have a named task and a named test.
- **Determinism contract enforced structurally:** `slashingEnabled` appears ONLY in Task 6 (definition) and Task 8 (miner fold). Tasks 4/5 explicitly forbid referencing it.
- **Type consistency:** `HotStuffEquivocationProof(voteA, voteB)` + `consistent`/`signaturesValid` (Task 1) used verbatim in Tasks 2, 4, 7, 8, 10; `detectedEquivocations`/`pruneEquivocations` (Task 7) consumed in Task 8; `foldHotStuffConflicts`'s six parameters identical in Task 8's test and implementation.
- **Known flex points flagged, not hidden:** test-keypair helper (Task 1), `Enabled` construction pattern (Task 7), exact `Blockchain` accessor names (Task 8), `GeneratorInfo.blsPublicKey` accessor (Task 7) — each step says exactly where to read the real answer instead of guessing.
- **Reference branch used correctly:** patterns cited by commit for Tasks 2/7/8; strict-decode and epoch rules deliberately diverge from it, with the reasons in the commit messages.
