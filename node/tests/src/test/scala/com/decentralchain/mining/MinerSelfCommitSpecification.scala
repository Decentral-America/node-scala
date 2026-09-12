package com.decentralchain.mining

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Pure-function tests for `Miner.shouldSelfCommit` — the in-process replacement decision for the
  * external auto-commit-generators/commit-generators-hotstuff GH Actions crons. See
  * docs/superpowers/plans/2026-09-12-inprocess-self-commit-generation.md.
  */
class MinerSelfCommitSpecification extends AnyFreeSpec with Matchers {

  "shouldSelfCommit" - {
    "feature disabled => never self-commit, regardless of committee membership" in {
      Miner.shouldSelfCommit(enabled = false, alreadyCommitted = false) shouldBe false
      Miner.shouldSelfCommit(enabled = false, alreadyCommitted = true) shouldBe false
    }

    "feature enabled, already committed => do not re-submit" in {
      Miner.shouldSelfCommit(enabled = true, alreadyCommitted = true) shouldBe false
    }

    "feature enabled, not yet committed => self-commit" in {
      Miner.shouldSelfCommit(enabled = true, alreadyCommitted = false) shouldBe true
    }
  }
}
