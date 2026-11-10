package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random

/**
 * Where a buffer a caller handed over is written so a platform reader can open it as a file.
 *
 * A file is named after a digest of the bytes it holds, so a probe, an export and a preview seek of one buffer all land
 * on one path and the buffer is written once. A backend supplies only the root directory.
 *
 * Files live in a directory of their own for each process, under `filmstrip-media-bytes` in the root. A path handed out
 * is never deleted while its process runs, so a buffer holds its size on disk until then. The first use in a process
 * removes the directories of processes that are gone and leaves those of processes still running alone. A file the OS
 * purged is written again on the next [fileFor], because existence and size are checked on every call. A file only
 * counts as already written when it is a regular file of the user's own, so a link at its name is replaced and never
 * read through.
 *
 * [fileFor] blocks its thread for the write, and for another thread's write of the same buffer, so a suspending caller
 * runs it on `Dispatchers.IO`.
 */
@InternalFilmstripApi
public object MediaBytesFile {
  /**
   * The path under [root] holding [source]'s bytes, named for the container [containerExtensionOf] reads.
   *
   * The digest is worked out once for each [source], so calling this again only costs a look at the file.
   *
   * @throws MediaBytesFileFailure when the directory or the file could not be written, or the directory can't be
   * trusted.
   */
  public fun fileFor(
    root: String,
    source: MediaSource.Bytes,
  ): String {
    val digest = source.memoizedDigest ?: digestOf(source.bytes).also { source.memoizedDigest = it }
    return write(root, source.bytes, digest, containerExtensionOf(source.bytes, source.hint))
  }

  /**
   * The path under [root] holding [bytes], written under [extension] if it is not there already.
   *
   * @throws MediaBytesFileFailure when the directory or the file could not be written.
   */
  public fun fileFor(
    root: String,
    bytes: ByteArray,
    extension: String,
  ): String = write(root, bytes, digestOf(bytes), extension)

  /**
   * Holds the file for [digest] under [root], with [writer] in place of the real write for a test to watch.
   *
   * The check and the write sit under a lock for that file, so concurrent first calls on one buffer write one copy and
   * nothing moves over a file a reader already opened.
   */
  internal fun write(
    root: String,
    bytes: ByteArray,
    digest: String,
    extension: String,
    writer: (ByteArray, Path) -> Unit = ::writeWhole,
  ): String =
    try {
      val file = Path(processDirectory(root), "$digest.$extension")
      writes.withLock(file.toString()) {
        if (entryAt(file)?.isWholeFile(bytes.size.toLong()) != true) writer(bytes, file)
      }
      file.toString()
    } catch (failure: Exception) {
      // kotlinx-io throws its IOException and a platform's security manager throws its own, and no backend tells them
      // apart.
      throw MediaBytesFileFailure(MediaSource.Bytes(bytes).describe(), failure.message, failure)
    }

  /**
   * Writes beside [file] and moves onto it, so a run dying part way leaves nothing a later one takes for a whole file.
   *
   * The partial's name is unique, so two threads writing one buffer never share a half written file.
   */
  private fun writeWhole(
    bytes: ByteArray,
    file: Path,
  ) {
    val partial = Path("$file.${Random.nextLong().toULong().toString(HEX)}$PARTIAL_SUFFIX")
    try {
      SystemFileSystem.sink(partial).buffered().use { it.write(bytes) }
      moveOnto(partial, file)
    } finally {
      SystemFileSystem.delete(partial, mustExist = false)
    }
  }

  private fun processDirectory(root: String): Path {
    val parent = Path(root, DIRECTORY)
    ensurePrivateDirectory(Path(root))
    ensurePrivateDirectory(parent)
    sweepOnce(parent)
    val directory = Path(parent, currentProcessId().toString())
    ensurePrivateDirectory(directory)
    return directory
  }

  @OptIn(ExperimentalAtomicApi::class)
  private fun sweepOnce(parent: Path) {
    val key = parent.toString()
    while (true) {
      val seen = swept.load()
      if (key in seen) return
      if (swept.compareAndSet(seen, seen + key)) break
    }
    sweep(parent)
  }

  /**
   * Removes what processes that are gone left under [parent].
   *
   * This process's own directory is left alone, so a thread writing into it never races the sweep. Anything left there
   * by an earlier process holding the same id is named for its own contents, so reading it is still correct. A failed
   * delete only means another process got there first, or that the next one tries again, so it never stops the others.
   */
  private fun sweep(parent: Path) {
    val own = currentProcessId()
    ignoringFailure {
      if (entryAt(parent)?.kind != MediaBytesEntryKind.Directory) return
      SystemFileSystem.list(parent).forEach { entry ->
        ignoringFailure { if (!isHeld(entry.name, own)) deleteEntry(entry) }
      }
    }
  }

  // kill(0) and kill(-1) signal a whole group and always succeed, so neither name can stand for a process.
  private fun isHeld(
    name: String,
    own: Int,
  ): Boolean {
    val id = name.toIntOrNull() ?: return false
    return id > 0 && (id == own || isProcessRunning(id))
  }

  /**
   * Removes [entry], descending only into a real directory, so a link goes and what it points at stays.
   *
   * kotlinx-io has no `openat` or `O_NOFOLLOW`, so its limit is that a link swapped in between the read of the entry
   * and the listing is listed through. That swap takes write access to the parent, which is `0700`, so only the same
   * user can make it.
   */
  private fun deleteEntry(entry: Path) {
    ignoringFailure {
      if (entryAt(entry)?.kind == MediaBytesEntryKind.Directory) SystemFileSystem.list(entry).forEach(::deleteEntry)
      removeEntry(entry)
    }
  }

  private inline fun ignoringFailure(block: () -> Unit) {
    try {
      block()
    } catch (_: Exception) {
      return
    }
  }

  private fun digestOf(bytes: ByteArray): String =
    sha256Of(bytes)
      .take(NAME_BYTES)
      .joinToString("") { byte -> byte.toUByte().toString(HEX).padStart(2, '0') }

  private val writes = KeyedLocks()

  @OptIn(ExperimentalAtomicApi::class)
  private val swept = AtomicReference<Set<String>>(emptySet())

  private const val DIRECTORY = "filmstrip-media-bytes"

  private const val PARTIAL_SUFFIX = ".partial"

  // Enough of the digest that two sources cannot land on one name, short enough that the name stays readable in a log.
  private const val NAME_BYTES = 16

  private const val HEX = 16
}

/**
 * Thrown when a buffer could not be written to a file a platform reader can open.
 *
 * Its message is the one every backend reports, whether it surfaces as a probe failure or as a lowering failure.
 *
 * @property source The buffer that could not be written, as an error message names it.
 * @param reason What the file system said, if it said anything.
 * @param cause The failure underneath.
 */
@InternalFilmstripApi
public class MediaBytesFileFailure(
  public val source: String,
  reason: String?,
  cause: Throwable? = null,
) : Exception("An in-memory source is written to a temporary file before it is read, and that failed: $reason", cause) {
  /**
   * The message every backend reports, never null.
   */
  override val message: String
    get() = super.message.orEmpty()
}
