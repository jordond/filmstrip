package dev.jordond.filmstrip.media

import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The SHA-256 of [bytes], through the platform's own implementation.
 *
 * Hardware backed on every target, which a buffer of hundreds of megabytes feels.
 */
internal expect fun sha256Of(bytes: ByteArray): ByteArray

/**
 * The id the OS knows this process by.
 *
 * It names the directory this process keeps its files in.
 */
internal expect fun currentProcessId(): Int

/**
 * True unless the OS says no process holds [id].
 *
 * A process this one may not signal still counts as running.
 */
internal expect fun isProcessRunning(id: Int): Boolean

/**
 * What sits at a path, as far as the file cache cares.
 */
internal enum class MediaBytesEntryKind {
  Directory,
  File,

  /**
   * A symbolic link, or anything else that is neither of the two.
   */
  Other,
}

/**
 * What an lstat says about a path, so a symbolic link reads as itself and never as what it points at.
 *
 * @property kind What the path holds.
 * @property size How many bytes the OS says the path holds.
 * @property ownedByUser Whether the user this process runs as owns the path.
 */
internal class MediaBytesEntry(
  val kind: MediaBytesEntryKind,
  val size: Long,
  val ownedByUser: Boolean,
) {
  /**
   * True when this is a file of the user's own holding exactly [expected] bytes.
   */
  fun isWholeFile(expected: Long): Boolean = kind == MediaBytesEntryKind.File && ownedByUser && size == expected
}

/**
 * Reads [path] without following a symbolic link, or null when nothing is there.
 */
internal expect fun entryAt(path: Path): MediaBytesEntry?

/**
 * Removes [path] itself without following a symbolic link. A directory has to be empty by now.
 */
internal expect fun removeEntry(path: Path)

/**
 * Renames [source] onto [target] in one step, replacing whatever file is at [target], so a reader opening [target]
 * finds either the old file or the whole new one.
 */
internal expect fun moveOnto(
  source: Path,
  target: Path,
)

/**
 * Creates [directory] and any of its parents that are missing, on every target.
 *
 * Android and Apple hand the cache a root only this app can reach, so this just creates it. A desktop JVM has a shared
 * temporary directory, where it creates each one for the owner alone and refuses a [directory] anybody else could have
 * made.
 *
 * @throws kotlinx.io.IOException when the directory can't be made or can't be trusted.
 */
internal expect fun ensurePrivateDirectory(directory: Path)

/**
 * Runs [create] for [directory] again for as long as it fails and nothing but a directory is at that path.
 *
 * Threads racing to make one directory lose in a way that looks like a failure. kotlinx-io throws when another thread
 * made it first, and macOS answers `mkdir` with "File exists" while a read of that name still finds nothing, until the
 * other thread is done. Anything else at the path ends the wait with the failure, and so does [DIRECTORY_RACE_BOUND].
 *
 * @throws kotlinx.io.IOException the last failure of [create] when it never succeeded.
 */
internal fun creatingThroughRaces(
  directory: Path,
  create: () -> Unit,
) {
  val started = TimeSource.Monotonic.markNow()
  while (true) {
    try {
      create()
      return
    } catch (failure: IOException) {
      val inTheWay = entryAt(directory)?.let { it.kind != MediaBytesEntryKind.Directory } == true
      if (inTheWay || started.elapsedNow() >= DIRECTORY_RACE_BOUND) throw failure
    }
  }
}

/**
 * How long [creatingThroughRaces] keeps trying a directory that does not show up.
 *
 * It only runs out when no thread is making that directory, like a parent nothing can write to.
 */
private val DIRECTORY_RACE_BOUND = 1.seconds

/**
 * A lock that blocks the thread waiting on it.
 */
internal expect class MediaBytesLock() {
  fun lock()

  fun unlock()
}
