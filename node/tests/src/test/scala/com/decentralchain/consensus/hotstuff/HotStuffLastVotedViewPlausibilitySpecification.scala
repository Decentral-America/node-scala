package com.decentralchain.consensus.hotstuff

import com.decentralchain.test.FlatSpec

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class HotStuffLastVotedViewPlausibilitySpecification extends FlatSpec {
  "HotStuffLastVotedViewStore.load" should "discard a persisted view above the plausible bound" in {
    val dir  = Files.createTempDirectory("hs-lvv")
    val path = dir.resolve("last-voted-view.dat")
    Files.write(path, Int.MaxValue.toString.getBytes(UTF_8))
    HotStuffLastVotedViewStore.load(path, maxPlausible = Int.MaxValue - (1 << 20) - 1) shouldBe None
  }

  it should "keep an ordinary persisted view" in {
    val dir  = Files.createTempDirectory("hs-lvv")
    val path = dir.resolve("last-voted-view.dat")
    Files.write(path, "382843".getBytes(UTF_8))
    HotStuffLastVotedViewStore.load(path, maxPlausible = Int.MaxValue - (1 << 20) - 1) shouldBe Some(382843)
  }
}
