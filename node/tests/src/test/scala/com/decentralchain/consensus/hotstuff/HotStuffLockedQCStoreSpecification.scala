package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffVote, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

import java.nio.file.{Files, Paths}

/** `HotStuffLockedQCStore` is the disk half of the post-restart `lockedQC=None` fix (see
  * `HotStuffSafety.safeToVote`'s doc comment and `HotStuffCoordinator.Enabled`'s `initialLockedQC` /
  * `onLockedQCPersist` params). This covers the store in isolation: round-trip fidelity, and every
  * failure mode degrading to "as if nothing were persisted" rather than throwing.
  */
class HotStuffLockedQCStoreSpecification extends FlatSpec {
  private val kps                     = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val B: BlockId = ByteStr(Array.fill[Byte](32)(7))

  private def realLockQC(view: Int, height: Int): QuorumCertificate = {
    val msg   = HotStuffQuorum.voteMessage(view, HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT, B, height)
    val votes = (0 to 2).map(i => HotStuffVote(view, HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT, B, Height(height), i, kps(i).sign(msg, BlsUtils.BlsDomainSeparationTag).byteStr))
    HotStuffQuorum.formQC(votes, committee).toOption.get
  }

  private def tempPath() = {
    val dir = Files.createTempDirectory("hotstuff-lockedqc-spec")
    dir.toFile.deleteOnExit()
    Paths.get(dir.toString, "locked-qc.dat")
  }

  "save then load" should "round-trip the exact QC (view, phase, blockId, blockHeight, signerIndexes, aggregatedSignature)" in {
    val path = tempPath()
    val qc   = realLockQC(view = 5, height = 500)

    HotStuffLockedQCStore.load(path) should be(None) // nothing written yet

    HotStuffLockedQCStore.save(path, qc)
    Files.exists(path) should be(true)

    HotStuffLockedQCStore.load(path) should be(Some(qc))
  }

  it should "let a second save overwrite the first (only the latest lock matters)" in {
    val path = tempPath()
    val qc1  = realLockQC(view = 5, height = 500)
    val qc2  = realLockQC(view = 9, height = 900)

    HotStuffLockedQCStore.save(path, qc1)
    HotStuffLockedQCStore.save(path, qc2)

    HotStuffLockedQCStore.load(path) should be(Some(qc2))
  }

  "load" should "return None (not throw) for a nonexistent file" in {
    val path = Paths.get(Files.createTempDirectory("hotstuff-lockedqc-spec-missing").toString, "does-not-exist.dat")
    noException should be thrownBy HotStuffLockedQCStore.load(path)
    HotStuffLockedQCStore.load(path) should be(None)
  }

  it should "return None (not throw) for a corrupted/truncated file" in {
    val path = tempPath()
    Files.write(path, Array[Byte](1, 2, 3, 4, 5)) // not a valid protobuf-encoded QuorumCertificate

    noException should be thrownBy HotStuffLockedQCStore.load(path)
    HotStuffLockedQCStore.load(path) should be(None)
  }

  "save" should "not throw when the target directory cannot be created (e.g. path under a file, not a directory)" in {
    val blocker = Files.createTempFile("hotstuff-lockedqc-spec-blocker", ".tmp")
    val path    = Paths.get(blocker.toString, "subdir", "locked-qc.dat") // blocker is a FILE, not a dir

    noException should be thrownBy HotStuffLockedQCStore.save(path, realLockQC(1, 100))
  }
}
