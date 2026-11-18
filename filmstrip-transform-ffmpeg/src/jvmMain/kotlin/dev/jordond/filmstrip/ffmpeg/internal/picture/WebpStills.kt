package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.effects.overlay.decodedPng
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.webpRgbaOf
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * How a still WebP codes its picture, which decides what ffmpeg is asked to hand back for it.
 */
internal sealed interface WebpStill {
  /**
   * A `VP8L` picture, which ffmpeg decodes to the exact samples it stores.
   */
  data object Lossless : WebpStill

  /**
   * A `VP8 ` picture of [size], which is read as the decoder's own planes and converted by core.
   *
   * @property size The size the frame header of the picture gives.
   * @property alpha Whether an `ALPH` chunk comes with it.
   */
  class Lossy(
    val size: Size,
    val alpha: Boolean,
  ) : WebpStill
}

/**
 * The [WebpStill] that [bytes] are, or null when they are not a still WebP whose coding this reads.
 *
 * An animated file, one with no picture chunk and one whose `VP8 ` frame header is not a key frame are not.
 */
internal fun webpStillOf(bytes: ByteArray): WebpStill? {
  if (bytes.size < WEBP_HEADER || !bytes.tagAt(0, "RIFF") || !bytes.tagAt(WEBP_FORM, "WEBP")) return null
  val end = minOf(bytes.size.toLong(), RIFF_HEADER + bytes.le32(RIFF_SIZE))
  var at = WEBP_HEADER.toLong()
  var alpha = false
  while (at + CHUNK_HEADER <= end) {
    val size = bytes.le32(at.toInt() + CHUNK_SIZE)
    val data = at.toInt() + CHUNK_HEADER
    when {
      bytes.tagAt(at.toInt(), "VP8X") -> if (bytes[data].toInt() and ANIMATION_FLAG != 0) return null
      bytes.tagAt(at.toInt(), "ALPH") -> alpha = true
      bytes.tagAt(at.toInt(), "VP8L") -> return WebpStill.Lossless
      bytes.tagAt(at.toInt(), "VP8 ") -> return lossyOf(bytes, data, size, alpha)
    }
    at += CHUNK_HEADER + size + (size and 1L)
  }
  return null
}

private fun lossyOf(
  bytes: ByteArray,
  data: Int,
  size: Long,
  alpha: Boolean,
): WebpStill? {
  if (size < VP8_HEADER || data + VP8_HEADER > bytes.size) return null
  val key = bytes[data].toInt() and KEY_FRAME_BIT == 0
  val startCode = (0 until START_CODE.size).all { bytes[data + START_CODE_AT + it] == START_CODE[it] }
  if (!key || !startCode) return null
  val width = bytes.le16(data + VP8_WIDTH_AT) and VP8_DIMENSION
  val height = bytes.le16(data + VP8_HEIGHT_AT) and VP8_DIMENSION
  return if (width > 0 && height > 0) WebpStill.Lossy(Size(width, height), alpha) else null
}

/**
 * Decodes every still of [stills] with one ffmpeg child, each one's bytes handed over as an input of its own and each
 * picture written to an output of its own, so frames of different sizes need no common size.
 *
 * A [WebpStill.Lossless] picture comes back as a PNG and goes through [decodedPng], the way a single decode reads it. A
 * [WebpStill.Lossy] one comes back as the decoder's raw `yuv420p` or `yuva420p` planes, which [webpRgbaOf] converts the
 * way libwebp does, so no swscale conversion touches its color or its last column. The child runs on one thread per
 * input and is stopped at [deadline], a System.nanoTime.
 *
 * @return One result for each of [stills], in order. A child that fails or is stopped gives every one the same
 * answer.
 */
@Throws(InterruptedException::class)
internal fun decodedWebpStills(
  ffmpeg: String,
  stills: List<Pair<ByteArray, WebpStill>>,
  deadline: Long,
  timeoutMillis: Long,
  scratch: File?,
  write: (File, ByteArray) -> Unit,
): List<OverlayPictureDecode<DecodedPicture>> {
  if (stills.isEmpty()) return emptyList()

  fun refusedAll(reason: String) = stills.map { OverlayPictureRead.Refused(reason) }

  val directory =
    try {
      (
        if (scratch ==
          null
        ) {
          Files.createTempDirectory(SCRATCH_PREFIX)
        } else {
          Files.createTempDirectory(scratch.toPath(), SCRATCH_PREFIX)
        }
      ).toFile()
    } catch (unwritable: IOException) {
      return refusedAll(unwritable.message.orEmpty())
    }
  try {
    val command = mutableListOf(ffmpeg, "-nostdin", "-y", "-hide_banner", "-loglevel", "error", "-noautorotate")
    stills.forEachIndexed { index, (bytes, _) ->
      val input = File(directory, "in$index.webp")
      try {
        write(input, bytes)
      } catch (unwritable: IOException) {
        return refusedAll(unwritable.message.orEmpty())
      }
      command += listOf("-threads", "1", "-i", input.path)
    }
    stills.forEachIndexed { index, (_, still) ->
      command += listOf("-map", "$index:v:0", "-frames:v", "1", "-vf", PASS_THROUGH)
      command += if (still is WebpStill.Lossy) planesOutput(still) else PNG_OUTPUT
      command += File(directory, "out$index").path
    }
    val read =
      ranToEnd(command, deadline, timeoutMillis, null, { it.readAllBytes() }) { _ ->
        stills.mapIndexed { index, (_, still) -> decodedStill(File(directory, "out$index"), still) }
      }
    return when (read) {
      is OverlayPictureRead.Read -> read.value
      is OverlayPictureRead.Refused -> refusedAll(read.reason)
      is OverlayPictureRead.Expired -> stills.map { read }
    }
  } finally {
    directory.deleteRecursively()
  }
}

private fun decodedStill(
  output: File,
  still: WebpStill,
): OverlayPictureDecode<DecodedPicture> {
  val written = output.takeIf { it.isFile }?.readBytes() ?: return OverlayPictureRead.Refused(NO_PICTURE)
  val picture =
    when (still) {
      WebpStill.Lossless -> decodedPng(written)
      is WebpStill.Lossy -> decodedPlanes(written, still)
    }
  return picture?.let { OverlayPictureRead.Read(it) } ?: OverlayPictureRead.Refused(NO_PICTURE)
}

// The picture the planes in [raw] make, or null when they are not the whole of the three or four planes of [still].
private fun decodedPlanes(
  raw: ByteArray,
  still: WebpStill.Lossy,
): DecodedPicture? {
  val width = still.size.width
  val height = still.size.height
  val texels = width * height
  val chroma = (width + 1) / 2 * ((height + 1) / 2)
  if (raw.size != texels + 2 * chroma + (if (still.alpha) texels else 0)) return null
  val rgba =
    webpRgbaOf(
      width,
      height,
      raw.copyOfRange(0, texels),
      raw.copyOfRange(texels, texels + chroma),
      raw.copyOfRange(texels + chroma, texels + 2 * chroma),
      if (still.alpha) raw.copyOfRange(texels + 2 * chroma, raw.size) else null,
    )
  val argb =
    IntArray(texels) {
      val at = it * RGBA
      ((rgba[at + 3].toInt() and BYTE) shl ALPHA_SHIFT) or ((rgba[at].toInt() and BYTE) shl RED_SHIFT) or
        ((rgba[at + 1].toInt() and BYTE) shl GREEN_SHIFT) or (rgba[at + 2].toInt() and BYTE)
    }
  return DecodedPicture(still.size, StoredSamples.Argb(argb), null)
}

private fun planesOutput(still: WebpStill.Lossy): List<String> =
  listOf("-pix_fmt", if (still.alpha) "yuva420p" else "yuv420p", "-f", "rawvideo")

private fun ByteArray.tagAt(
  at: Int,
  tag: String,
): Boolean = size >= at + TAG && tag.indices.all { this[at + it] == tag[it].code.toByte() }

private fun ByteArray.le16(at: Int): Int =
  (this[at].toInt() and BYTE) or ((this[at + 1].toInt() and BYTE) shl BYTE_BITS)

private fun ByteArray.le32(at: Int): Long = (le16(at).toLong()) or (le16(at + 2).toLong() shl 2 * BYTE_BITS)

private val PNG_OUTPUT = listOf("-c:v", "png", "-f", "image2pipe")

private val START_CODE = byteArrayOf(0x9D.toByte(), 0x01, 0x2A)

private const val NO_PICTURE = "ffmpeg wrote no picture for this WebP frame."
private const val SCRATCH_PREFIX = "filmstrip-overlay-frames"
private const val RIFF_HEADER = 8L
private const val RIFF_SIZE = 4
private const val WEBP_FORM = 8
private const val WEBP_HEADER = 12
private const val CHUNK_HEADER = 8
private const val CHUNK_SIZE = 4
private const val TAG = 4
private const val ANIMATION_FLAG = 0x02
private const val KEY_FRAME_BIT = 0x01
private const val START_CODE_AT = 3
private const val VP8_WIDTH_AT = 6
private const val VP8_HEIGHT_AT = 8
private const val VP8_HEADER = 10
private const val VP8_DIMENSION = 0x3FFF
private const val RGBA = 4
private const val BYTE = 0xFF
private const val BYTE_BITS = 8
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
