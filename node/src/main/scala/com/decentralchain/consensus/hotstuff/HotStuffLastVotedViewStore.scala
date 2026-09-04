package com.decentralchain.consensus.hotstuff

import com.typesafe.scalalogging.StrictLogging

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.util.control.NonFatal

/** Persists this replica's HotStuff `SafetyState.lastVotedView` to a small local file, and reloads it
  * on the next process start.
  *
  * WHY (finding M1): `HotStuffCoordinator.Enabled` used to always boot with `lastVotedView = -1`, same
  * as any other field of a blank `SafetyState()`. As documented at `HotStuffSafety.safeToVote` and the
  * "RESIDUAL GAP" note this store closes on `resetLocalSafetyState`'s doc, `lastVotedView` is the ONLY
  * thing on the PREPARE-vote path stopping a replica from casting two conflicting votes in the SAME
  * view -- and it is purely in-memory, so a process restart reopens the exact double-vote window the
  * watchdog's `resetLocalSafetyState` fix (audit F-2) closes for an in-process reset. Concretely: a
  * replica votes PREPARE for block A at view `v`, crashes or is restarted before a QC comes back, boots
  * with `lastVotedView = -1`, and then honestly votes PREPARE for a DIFFERENT block B still at view `v`
  * -- because `v > -1` passes `HotStuffSafety.safeToVote` unconditionally. Two conflicting signed votes
  * at the same `(view, phase)` now exist on the wire, precisely what `HotStuffSafety.equivocators`
  * exists to detect. While detection is observational-only this is merely a false-positive alert; once
  * slashing is enabled (`HotStuffSettings.slashingEnabled`) it becomes real deposit forfeiture for an
  * HONEST operator who did nothing wrong except restart. This store closes the restart window: the
  * coordinator persists `lastVotedView` every time it genuinely advances (see
  * `HotStuffCoordinator.Enabled`'s `onLastVotedViewPersist` hook) and reloads it at startup
  * (`initialLastVotedView`), so a restart resumes from its last real voted view instead of `-1`.
  *
  * RESIDUAL GAP (T11, unavoidable): a replica's very first-ever boot has no persisted value to load, so
  * `initialLastVotedView` still defaults to `-1` for it -- see `HotStuffSettings.slashingEnabled`'s
  * OPERATIONAL NOTE. That window exists exactly once per replica, at genesis-of-participation, not on
  * every restart, which is what this store fixes.
  *
  * FORMAT: `view.toString` as UTF-8 bytes -- a single decimal integer, no schema needed. Written
  * atomically (temp file + `ATOMIC_MOVE`) so a crash mid-write can never leave a partially-written,
  * corrupt file behind.
  *
  * FAILURE HANDLING: every failure mode (no file yet, corrupt/non-numeric data, a write that fails) is
  * handled by logging and falling back to "as if nothing were persisted" -- `load` returns `None` and
  * `save` simply skips the write. This mirrors `HotStuffLockedQCStore`'s handling of its own file (and,
  * further back, `PeerDatabaseImpl`'s handling of a corrupt `peers.dat`: log + continue). Never fails/
  * crashes node startup, and never blocks consensus progress: the in-memory `lastVotedView` (advanced
  * fresh at runtime via `HotStuffSafety.update`) is authoritative regardless of whether the disk copy is
  * readable.
  *
  * NOT invoked at all when `dcc.hotstuff.enabled = false` (the default): `Application.scala` only
  * constructs a `HotStuffCoordinator.Enabled` (and therefore only wires this store) inside the
  * `if (settings.hotStuffSettings.enabled)` block.
  */
object HotStuffLastVotedViewStore extends StrictLogging {

  /** Load the persisted lastVotedView, if any. Never throws. */
  def load(path: Path): Option[Int] =
    if (!Files.exists(path)) None
    else
      try {
        val view = new String(Files.readAllBytes(path), UTF_8).trim.toInt
        logger.info(s"[HotStuff] restored lastVotedView=$view from $path")
        Some(view)
      } catch {
        case NonFatal(e) =>
          logger.warn(s"[HotStuff] failed to load persisted lastVotedView from $path -- starting with lastVotedView=-1: ${e.getMessage}")
          None
      }

  /** Persist `view` as the new lastVotedView, atomically. Never throws (a failed write just means the
    * next restart falls back to whatever was last successfully persisted, or `-1` -- never worse than
    * today's unconditional `-1`).
    */
  def save(path: Path, view: Int): Unit =
    try {
      Option(path.getParent).foreach(Files.createDirectories(_))
      val tmp = Files.createTempFile(path.getParent, s"${path.getFileName}", ".tmp")
      try {
        Files.write(tmp, view.toString.getBytes(UTF_8))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      } finally {
        Files.deleteIfExists(tmp) // no-op if the move above already consumed it
      }
    } catch {
      case NonFatal(e) =>
        logger.warn(
          s"[HotStuff] failed to persist lastVotedView to $path (in-memory view is unaffected; will retry on next advance): ${e.getMessage}"
        )
    }
}
