package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.malformed
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Reads up to [count] bytes of a file starting at `start`, handing back fewer when the file ends first.
 */
internal typealias ByteRangeRead = suspend (start: Long, count: Int) -> ByteArray

/**
 * A picture file of [size] bytes that a container walk reads ranges of.
 *
 * [read] is only ever asked for a range inside `0..size` no longer than [MAX_WALK_BLOCK], and hands back fewer bytes
 * only when the file turns out to end before [size].
 */
internal interface PictureBytes {
  val size: Long

  suspend fun read(
    start: Long,
    count: Int,
  ): ByteArray
}

/**
 * A whole picture already in memory.
 */
internal class ByteArrayPicture(
  private val bytes: ByteArray,
) : PictureBytes {
  override val size: Long get() = bytes.size.toLong()

  override suspend fun read(
    start: Long,
    count: Int,
  ): ByteArray = bytes.copyOfRange(start.toInt(), start.toInt() + count)
}

/**
 * A picture of [size] bytes that [read] fetches ranges of, such as a seekable stream or a blob.
 */
internal class RangedPicture(
  override val size: Long,
  private val read: ByteRangeRead,
) : PictureBytes {
  override suspend fun read(
    start: Long,
    count: Int,
  ): ByteArray = read.invoke(start, count)
}

/**
 * A bounded reader over [picture] that container walks read fields out of by their place in the file.
 *
 * Small reads are served from one block of at least [WALK_READ_AHEAD] bytes, so the headers of short segments, chunks
 * and boxes that sit next to each other cost one read between them. The walk asks [picture] for at most
 * [MAX_WALK_READS] ranges and [MAX_WALK_BYTES] bytes in all, and for no more than [MAX_WALK_BLOCK] at once. A walk that
 * needs more, a read outside the file or past a limit it is given, and a file that ends before its size all throw
 * [MalformedPicture]. The coroutine is checked for cancellation ahead of every read.
 */
internal class PictureWalk(
  private val picture: PictureBytes,
) {
  val size: Long = picture.size

  private var reads = 0
  private var bytesRead = 0L
  private var cached = ByteArray(0)
  private var cachedAt = 0L
  private var cachedLength = 0

  suspend fun matches(
    at: Long,
    expected: ByteArray,
    limit: Long = size,
  ): Boolean {
    if (at < 0 || minOf(limit, size) - at < expected.size) return false
    val view = load(at, expected.size)
    val offset = (at - cachedAt).toInt()
    return expected.indices.all { view.bytes[offset + it] == expected[it] }
  }

  suspend fun u8(
    at: Long,
    limit: Long = size,
  ): Int = field(at, 1, limit).let { (view, offset) -> view.u8(offset) }

  suspend fun u16(
    at: Long,
    little: Boolean = false,
    limit: Long = size,
  ): Int = field(at, 2, limit).let { (view, offset) -> view.u16(offset, little) }

  suspend fun u32(
    at: Long,
    little: Boolean = false,
    limit: Long = size,
  ): Long = field(at, 4, limit).let { (view, offset) -> view.u32(offset, little) }

  suspend fun u64(
    at: Long,
    little: Boolean = false,
    limit: Long = size,
  ): Long = field(at, 8, limit).let { (view, offset) -> view.u64(offset, little) }

  /**
   * The [count] bytes at [start] as a view of their own, which may share the walk's block rather than copy it.
   */
  suspend fun block(
    start: Long,
    count: Long,
  ): ByteView {
    if (count < 0 || count > MAX_WALK_BLOCK) malformed()
    val view = load(start, count.toInt())
    val offset = (start - cachedAt).toInt()
    if (offset == 0) return ByteView(view.bytes, count.toInt())
    return ByteView(view.bytes.copyOfRange(offset, offset + count.toInt()), count.toInt())
  }

  private suspend fun field(
    at: Long,
    count: Int,
    limit: Long,
  ): Pair<ByteView, Int> {
    if (at < 0 || at > minOf(limit, size) - count) malformed()
    val view = load(at, count)
    return view to (at - cachedAt).toInt()
  }

  // The block holding [count] bytes at [start], read afresh when the one held does not cover them.
  private suspend fun load(
    start: Long,
    count: Int,
  ): ByteView {
    if (start >= cachedAt && start + count <= cachedAt + cachedLength) return ByteView(cached, cachedLength)
    if (start < 0 || count < 0 || count > size - start) malformed()
    val wanted = minOf(maxOf(count, WALK_READ_AHEAD).toLong(), size - start).toInt()
    if (reads >= MAX_WALK_READS || bytesRead + wanted > MAX_WALK_BYTES) malformed()
    currentCoroutineContext().ensureActive()
    reads++
    bytesRead += wanted
    val bytes = picture.read(start, wanted)
    if (bytes.size < count) malformed()
    cached = bytes
    cachedAt = start
    cachedLength = minOf(bytes.size, wanted)
    return ByteView(cached, cachedLength)
  }
}

/**
 * Runs [walk] over [picture] and hands back what it found, or null when the picture is malformed or the walk runs
 * past its budget.
 */
internal suspend fun <T> walkPicture(
  picture: PictureBytes,
  walk: suspend PictureWalk.() -> T,
): T? =
  try {
    PictureWalk(picture).walk()
  } catch (_: MalformedPicture) {
    null
  }

/**
 * Runs [block] to the end on the calling thread. Only for a walk over a [ByteArrayPicture], which never suspends.
 */
internal fun <T> completeInPlace(block: suspend () -> T): T {
  var outcome: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
  return checkNotNull(outcome) { "A walk over bytes in memory suspended." }.getOrThrow()
}

/**
 * The most ranges one walk asks a picture for, enough for the 8 KiB image data chunks of a PNG of 512 MiB.
 */
internal const val MAX_WALK_READS: Int = 1 shl 16

/**
 * The most bytes one walk reads out of a picture in all, 16 MiB.
 */
internal const val MAX_WALK_BYTES: Long = 16L shl 20

/**
 * The most bytes one walk reads out of a picture at once, 4 MiB, which a tag chunk or `meta` box must fit inside.
 */
internal const val MAX_WALK_BLOCK: Int = 4 shl 20

/**
 * How many bytes a walk reads at the least when it fetches a field, so the fields that follow it come along.
 */
internal const val WALK_READ_AHEAD: Int = 64
