package com.decentralchain.consensus.hotstuff

import com.decentralchain.network.QuorumCertificate
import com.typesafe.scalalogging.StrictLogging

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.util.control.NonFatal

/** Persists this replica's HotStuff safety-lock (`SafetyState.lockedQC`) to a small local file, and
  * reloads it on the next process start.
  *
  * WHY: `HotStuffCoordinator.Enabled` used to always boot with a blank `SafetyState()` (`lockedQC =
  * None`). As documented at `HotStuffSafety.safeToVote`'s `None` branch, that blank-slate window lets a
  * freshly-restarted replica vote for ANY view-ordering-valid proposal -- including a Byzantine leader's
  * replay of an old-but-real on-chain block under an inflated view -- until the replica re-accumulates
  * its own lock. This store closes that window: the coordinator persists its lock every time it
  * genuinely advances (see `HotStuffCoordinator.Enabled`'s `onLockedQCPersist` hook) and reloads it at
  * startup (`initialLockedQC`), so a restart resumes from its last real lock instead of nothing.
  *
  * FORMAT: raw protobuf bytes (`QuorumCertificate.toProtobuf.toByteArray` / `parseFrom`), reusing the
  * exact wire encoding already defined for network transport (`network/messages.scala`) -- no new
  * schema, no JSON codec for the protobuf `HotStuffPhase` enum needed. Written atomically (temp file +
  * `ATOMIC_MOVE`) so a crash mid-write can never leave a partially-written, corrupt file behind.
  *
  * FAILURE HANDLING: every failure mode (no file yet, corrupt/truncated data, unreadable protobuf, a
  * write that fails) is handled by logging and falling back to "as if nothing were persisted" --
  * `load` returns `None` and `save` simply skips the write. This mirrors `PeerDatabaseImpl`'s handling
  * of a corrupt `peers.dat` (log + continue). Never fails/crashes node startup, and never blocks
  * consensus progress: the in-memory lock (recovered fresh at runtime, same as before this change, via
  * `HotStuffEngine.onQC`) is authoritative regardless of whether the disk copy is readable.
  *
  * NOT invoked at all when `dcc.hotstuff.enabled = false` (the default): `Application.scala` only
  * constructs a `HotStuffCoordinator.Enabled` (and therefore only wires this store) inside the
  * `if (settings.hotStuffSettings.enabled)` block. `HotStuffCoordinator.Disabled` never touches this
  * object, so no file is ever read or written while HotStuff is gated off.
  */
object HotStuffLockedQCStore extends StrictLogging {

  /** Load the persisted lockedQC, if any. Never throws. */
  def load(path: Path): Option[QuorumCertificate] =
    if (!Files.exists(path)) None
    else
      try {
        val pb = io.decentralchain.protobuf.block.QuorumCertificate.parseFrom(Files.readAllBytes(path))
        val qc = QuorumCertificate.fromProtobuf(pb)
        logger.info(s"[HotStuff] restored lockedQC from $path: $qc")
        Some(qc)
      } catch {
        case NonFatal(e) =>
          logger.warn(s"[HotStuff] failed to load persisted lockedQC from $path -- starting with lockedQC=None: ${e.getMessage}")
          None
      }

  /** Persist `qc` as the new lockedQC, atomically. Never throws (a failed write just means the next
    * restart falls back to whatever was last successfully persisted, or `None` -- never worse than
    * today's unconditional `None`).
    */
  def save(path: Path, qc: QuorumCertificate): Unit =
    try {
      Option(path.getParent).foreach(Files.createDirectories(_))
      val tmp = Files.createTempFile(path.getParent, s"${path.getFileName}", ".tmp")
      try {
        Files.write(tmp, qc.toProtobuf.toByteArray)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      } finally {
        Files.deleteIfExists(tmp) // no-op if the move above already consumed it
      }
    } catch {
      case NonFatal(e) =>
        logger.warn(s"[HotStuff] failed to persist lockedQC to $path (in-memory lock is unaffected; will retry on next advance): ${e.getMessage}")
    }
}
