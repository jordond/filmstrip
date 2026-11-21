package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.internal.sampleTableOverrunOf
import dev.jordond.filmstrip.internal.truncatedSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe

/**
 * The ways a source can be unreadable, each built from the bytes of a whole MP4 written with its `moov` before its
 * `mdat`.
 *
 * A backend is checked against every case for how soon it gives up and with what error.
 */
@InternalFilmstripApi
public enum class UnreadableCase {
  /**
   * The `moov` is whole and the `mdat` stops halfway, the shape of a recording cut off after its header was written.
   */
  TruncatedMdat,

  /**
   * Every box but the `moov`, the shape of a recording cut off before its header was written.
   */
  NoMoov,

  /**
   * An empty file.
   */
  ZeroBytes,

  /**
   * A kilobyte of text with an `.mp4` name.
   */
  NotVideo,

  /**
   * A file that does not exist, so [bytesFrom] gives null and a test points at a path nothing was written to.
   */
  Missing,
  ;

  /**
   * This case built from [mp4], or null for [Missing].
   *
   * Fails when [mp4] has no top level `moov` and `mdat` in that order.
   */
  public fun bytesFrom(mp4: ByteArray): ByteArray? =
    when (this) {
      TruncatedMdat -> {
        val mdat = topLevelBoxes(mp4).single { it.type == "mdat" }
        mp4.copyOfRange(0, mdat.payloadStart + (mdat.end - mdat.payloadStart) / 2)
      }
      NoMoov -> {
        val moov = topLevelBoxes(mp4).single { it.type == "moov" }
        mp4.copyOfRange(0, moov.start) + mp4.copyOfRange(moov.end, mp4.size)
      }
      ZeroBytes -> {
        ByteArray(0)
      }
      NotVideo -> {
        ByteArray(TEXT_BYTES) { TEXT[it % TEXT.length].code.toByte() }
      }
      Missing -> {
        null
      }
    }

  /**
   * The message a prober refuses this case at [source] with, given the [bytes] [bytesFrom] built, or null for a case a
   * prober turns away for its own reasons.
   *
   * Only [TruncatedMdat] meets the shared check, since its `moov` still opens and lists every sample. Fails when the
   * check finds nothing missing from it.
   */
  public suspend fun refusalAt(
    source: MediaSource,
    bytes: ByteArray?,
  ): String? {
    if (this != TruncatedMdat || bytes == null) return null
    val missing =
      checkNotNull(sampleTableOverrunOf(bytes.size.toLong()) { start, count -> bytes.rangeOf(start, count) }) {
        "The truncated file is missing nothing by its sample table."
      }
    return truncatedSource(source.describe(), missing).message
  }
}

private fun ByteArray.rangeOf(
  start: Long,
  count: Int,
): ByteArray = copyOfRange(start.toInt(), minOf(size, start.toInt() + count))

private class Box(
  val type: String,
  val start: Int,
  val payloadStart: Int,
  val end: Int,
)

private fun topLevelBoxes(mp4: ByteArray): List<Box> {
  val boxes = mutableListOf<Box>()
  var at = 0
  while (at + HEADER <= mp4.size) {
    val size = mp4.uint32At(at)
    val type = (4 until HEADER).map { mp4[at + it].toInt().toChar() }.joinToString("")
    val (length, header) =
      when (size) {
        0L -> (mp4.size - at).toLong() to HEADER
        1L -> (mp4.uint32At(at + HEADER) shl 32 or mp4.uint32At(at + HEADER + 4)) to HEADER + 8
        else -> size to HEADER
      }
    boxes += Box(type, at, at + header, at + length.toInt())
    at += length.toInt()
  }
  check(boxes.indexOfFirst { it.type == "moov" } in 0 until boxes.indexOfFirst { it.type == "mdat" }) {
    "expected a moov before the mdat, read ${boxes.map { it.type }}"
  }
  return boxes
}

private fun ByteArray.uint32At(at: Int): Long =
  (0 until 4).fold(0L) { value, i ->
    value shl 8 or
      (this[at + i].toLong() and 0xFF)
  }

private const val HEADER = 8
private const val TEXT_BYTES = 1024
private const val TEXT = "not a video, just some text. "
