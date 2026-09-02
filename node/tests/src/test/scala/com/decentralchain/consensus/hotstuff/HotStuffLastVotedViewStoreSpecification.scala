package com.decentralchain.consensus.hotstuff

import com.decentralchain.test.FlatSpec

import java.nio.file.{Files, Paths}

/** `HotStuffLastVotedViewStore` is the disk half of the M1 fix (see `HotStuffCoordinator.Enabled`'s
  * `initialLastVotedView` / `onLastVotedViewPersist` params and this store's own doc comment). This
  * covers the store in isolation: round-trip fidelity, and every failure mode degrading to "as if
  * nothing were persisted" rather than throwing -- mirroring `HotStuffLockedQCStoreSpecification`.
  */
class HotStuffLastVotedViewStoreSpecification extends FlatSpec {

  private def tempPath() = {
    val dir = Files.createTempDirectory("hotstuff-lastvotedview-spec")
    dir.toFile.deleteOnExit()
    Paths.get(dir.toString, "last-voted-view.dat")
  }

  "save then load" should "round-trip the exact view" in {
    val path = tempPath()

    HotStuffLastVotedViewStore.load(path) should be(None) // nothing written yet

    HotStuffLastVotedViewStore.save(path, 42)
    Files.exists(path) should be(true)

    HotStuffLastVotedViewStore.load(path) should be(Some(42))
  }

  it should "let a second save overwrite the first (only the latest view matters)" in {
    val path = tempPath()

    HotStuffLastVotedViewStore.save(path, 5)
    HotStuffLastVotedViewStore.save(path, 9)

    HotStuffLastVotedViewStore.load(path) should be(Some(9))
  }

  "load" should "return None (not throw) for a nonexistent file" in {
    val path = Paths.get(Files.createTempDirectory("hotstuff-lastvotedview-spec-missing").toString, "does-not-exist.dat")
    noException should be thrownBy HotStuffLastVotedViewStore.load(path)
    HotStuffLastVotedViewStore.load(path) should be(None)
  }

  it should "return None (not throw) for a corrupted/non-numeric file" in {
    val path = tempPath()
    Files.write(path, "not-a-number".getBytes("UTF-8"))

    noException should be thrownBy HotStuffLastVotedViewStore.load(path)
    HotStuffLastVotedViewStore.load(path) should be(None)
  }

  "save" should "not throw when the target directory cannot be created (e.g. path under a file, not a directory)" in {
    val blocker = Files.createTempFile("hotstuff-lastvotedview-spec-blocker", ".tmp")
    val path    = Paths.get(blocker.toString, "subdir", "last-voted-view.dat") // blocker is a FILE, not a dir

    noException should be thrownBy HotStuffLastVotedViewStore.save(path, 1)
  }
}
