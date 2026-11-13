@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.webcodecs.internal.interop.ArrayBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.interop.fetch
import dev.jordond.filmstrip.webcodecs.internal.interop.toByteArray
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The bytes of a source as a container walk reads them, a range at a time.
 *
 * A [SourceReader] keeps one beside its demuxer, so what the demuxer does not report can be read off the file itself.
 */
internal fun interface HeaderBytes {
  /**
   * Up to [count] bytes starting at [start], fewer when the file ends first.
   */
  suspend fun read(
    start: Long,
    count: Int,
  ): ByteArray
}

/**
 * Reads ranges of [bytes], which are already in memory.
 */
internal fun headerBytesOf(bytes: ByteArray): HeaderBytes = HeaderBytes { start, count -> bytes.rangeOf(start, count) }

/**
 * Reads ranges of [buffer], copying only the range asked for out of the JavaScript heap.
 */
internal fun headerBytesOf(buffer: ArrayBuffer): HeaderBytes {
  val view = Uint8Array(buffer)
  return HeaderBytes { start, count ->
    val from = start.coerceIn(0L, view.length.toLong()).toInt()
    view.subarray(from, minOf(view.length.toLong(), from + count.toLong()).toInt()).toByteArray()
  }
}

/**
 * Reads ranges of the file at [url] with `Range` requests.
 */
internal fun headerBytesOf(url: String): HeaderBytes = RangedUrl { start, count -> fetchRange(url, start, count) }

/**
 * What a server sent back for one range of a file.
 *
 * @property bytes The body of the answer.
 * @property isWhole Whether the body is the whole file, which is what a server that ignores `Range` sends.
 */
internal class RangeAnswer(
  val bytes: ByteArray,
  val isWhole: Boolean,
)

/**
 * Reads ranges of a file that [fetch] asks a server for.
 *
 * A walk reads a few dozen bytes at a time, so a short read is served from blocks of [RANGE_BLOCK] bytes that are
 * fetched once and kept. A read longer than a block is fetched as it is. Once a server answers with the whole file
 * that body is kept and every later read is a slice of it, so such a server is asked once.
 */
internal class RangedUrl(
  private val fetch: suspend (start: Long, count: Int) -> RangeAnswer,
) : HeaderBytes {
  private val blocks = mutableMapOf<Long, ByteArray>()
  private var whole: ByteArray? = null

  override suspend fun read(
    start: Long,
    count: Int,
  ): ByteArray {
    whole?.let { return it.rangeOf(start, count) }
    if (count > RANGE_BLOCK) return ranged(start, count) ?: read(start, count)
    val out = ByteArray(count)
    var filled = 0
    while (filled < count) {
      val at = start + filled
      val index = at / RANGE_BLOCK
      val block = blocks[index] ?: ranged(index * RANGE_BLOCK, RANGE_BLOCK) ?: return read(start, count)
      blocks[index] = block
      val offset = (at - index * RANGE_BLOCK).toInt()
      val taken = minOf(block.size - offset, count - filled)
      if (taken <= 0) break
      block.copyInto(out, filled, offset, offset + taken)
      filled += taken
    }
    return if (filled == count) out else out.copyOf(filled)
  }

  // The range a server sent, or null once it sent the whole file instead, which is kept.
  private suspend fun ranged(
    start: Long,
    count: Int,
  ): ByteArray? {
    val answer = fetch(start, count)
    if (!answer.isWhole) return answer.bytes
    whole = answer.bytes
    blocks.clear()
    return null
  }
}

/**
 * How many bytes [RangedUrl] asks a server for at the least, 64 KiB.
 */
internal const val RANGE_BLOCK: Int = 64 shl 10

private suspend fun fetchRange(
  url: String,
  start: Long,
  count: Int,
): RangeAnswer {
  val range = JsOptions().put("Range", "bytes=$start-${start + count - 1}").build()
  val response = fetch(url, JsOptions().put("headers", range).build()).await()
  val whole = response.status == HTTP_OK
  check(whole || response.status == HTTP_PARTIAL) { "The server answered ${response.status} for a range of $url." }
  return RangeAnswer(Uint8Array(response.arrayBuffer().await()).toByteArray(), whole)
}

private fun ByteArray.rangeOf(
  start: Long,
  count: Int,
): ByteArray {
  val from = start.coerceIn(0L, size.toLong()).toInt()
  return copyOfRange(from, minOf(size.toLong(), from + count.toLong()).toInt())
}

private const val HTTP_OK = 200
private const val HTTP_PARTIAL = 206
