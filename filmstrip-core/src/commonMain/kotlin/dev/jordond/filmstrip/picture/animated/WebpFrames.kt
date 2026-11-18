package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.RIFF_CHUNK_HEADER
import dev.jordond.filmstrip.picture.WEBP_FIRST_CHUNK
import dev.jordond.filmstrip.picture.WEBP_VP8X
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.ascii
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads an animated WebP into its frames, each rebuilt as a still WebP for a backend to decode.
 *
 * A file with no animation flag in its `VP8X`, or with one frame, is a still. A frame's chunks are its `ALPH` and
 * `VP8 ` or its `VP8L`, and a frame with an `ALPH` gets a `VP8X` header ahead of them that sets the alpha flag and the
 * frame's size, which a decoder needs to read the alpha. Frames dispose to nothing or to the background, and blend over
 * the canvas or replace it. The file's `ANIM` chunk must be there, and the file must hold every chunk it declares.
 */
internal fun ByteView.readWebp(): AnimatedRead {
  var animated = false
  try {
    val riffEnd = minOf(u32(RIFF_SIZE_AT, little = true) + RIFF_CHUNK_HEADER, end.toLong()).toInt()
    if (u32(WEBP_FIRST_CHUNK.toInt() + CHUNK_TYPE_AT).toInt() != WEBP_VP8X) return AnimatedRead.Still
    val header = WEBP_FIRST_CHUNK.toInt() + RIFF_CHUNK_HEADER.toInt()
    if (u8(header) and ANIMATION_FLAG == 0) return AnimatedRead.Still
    animated = true
    val canvas = Size(u24(header + CANVAS_WIDTH_AT) + 1, u24(header + CANVAS_HEIGHT_AT) + 1)
    var loops: Int? = null
    val frames = ArrayList<StoredFrame>()
    val delays = ArrayList<Duration>()
    var at = chunkEnd(WEBP_FIRST_CHUNK.toInt())
    while (at < riffEnd) {
      val type = u32(at).toInt()
      val size = u32(at + CHUNK_SIZE_AT, little = true)
      val data = at + RIFF_CHUNK_HEADER.toInt()
      val stop = span(data, size)
      when (type) {
        ANIM -> {
          loops = u16(data + ANIM_LOOPS_AT, little = true)
        }
        ANMF -> {
          val frame = anmf(data, stop)
          frames += frame.first
          delays += frame.second
        }
      }
      at = chunkEnd(at)
    }
    if (frames.isEmpty() || loops == null) malformed()
    if (frames.size == 1) return AnimatedRead.Still
    frames.forEachIndexed { index, frame ->
      if (!frame.region.liesWithin(canvas)) return AnimatedRead.Refused(animatedFrameOutside(index))
    }
    val timeline = FrameTimeline(delays, framePlays(PictureContainer.Webp, loops))
    return AnimatedRead.Animated(AnimatedPicture(canvas, frames, timeline))
  } catch (_: MalformedPicture) {
    return if (animated) AnimatedRead.Refused(ANIMATED_UNREADABLE) else AnimatedRead.Still
  }
}

private fun ByteView.u24(at: Int): Int = u16(at, little = true) or (u8(at + 2) shl 16)

// Where the chunk at [at] ends once its pad byte is counted, or the end of the file when that is nearer.
private fun ByteView.chunkEnd(at: Int): Int {
  val size = u32(at + CHUNK_SIZE_AT, little = true)
  val stop = span(at + RIFF_CHUNK_HEADER.toInt(), size)
  return minOf(stop + (size.toInt() and 1), end)
}

private fun ByteView.anmf(
  data: Int,
  stop: Int,
): Pair<StoredFrame, Duration> {
  val region =
    PictureCrop(
      u24(data) * 2,
      u24(data + 3) * 2,
      Size(u24(data + 6) + 1, u24(data + 9) + 1),
    )
  val delay = shownFrameDelay(PictureContainer.Webp, u24(data + 12).milliseconds)
  val flags = u8(data + 15)
  val blend = if (flags and NO_BLEND_FLAG != 0) FrameBlend.Source else FrameBlend.Over
  val disposal = if (flags and DISPOSE_BACKGROUND_FLAG != 0) FrameDisposal.Background else FrameDisposal.None
  var alpha: Int? = null
  var image: Int? = null
  var imageEnd = 0
  var at = data + ANMF_HEADER_SIZE
  while (at < stop) {
    val type = u32(at).toInt()
    val size = u32(at + CHUNK_SIZE_AT, little = true)
    val next = minOf(span(at + RIFF_CHUNK_HEADER.toInt(), size) + (size.toInt() and 1), stop)
    if (image == null) {
      when (type) {
        ALPH -> {
          alpha = at
        }
        VP8, VP8L -> {
          image = at
          imageEnd = next
        }
      }
    }
    at = next
  }
  val imageAt = image ?: malformed()
  val alphaAt = alpha?.takeIf { u32(imageAt).toInt() == VP8 }
  val chunks = bytes.copyOfRange(alphaAt ?: imageAt, imageEnd)
  val still = if (alphaAt != null) withHeader(chunks, region.size) else wrapped(chunks)
  return StoredFrame(region, blend, disposal, FramePixels.WebpStill(still)) to delay
}

// A still WebP holding just [chunks].
private fun wrapped(chunks: ByteArray): ByteArray = riff(ByteArray(0), chunks)

// A still WebP holding [chunks] behind a `VP8X` chunk that sets the alpha flag and [size].
private fun withHeader(
  chunks: ByteArray,
  size: Size,
): ByteArray {
  val vp8x = ByteArray(RIFF_CHUNK_HEADER.toInt() + VP8X_SIZE)
  ascii("VP8X").copyInto(vp8x)
  vp8x[CHUNK_SIZE_AT] = VP8X_SIZE.toByte()
  vp8x[RIFF_CHUNK_HEADER.toInt()] = ALPHA_FLAG.toByte()
  writeU24(vp8x, RIFF_CHUNK_HEADER.toInt() + CANVAS_WIDTH_AT, size.width - 1)
  writeU24(vp8x, RIFF_CHUNK_HEADER.toInt() + CANVAS_HEIGHT_AT, size.height - 1)
  return riff(vp8x, chunks)
}

private fun riff(
  header: ByteArray,
  chunks: ByteArray,
): ByteArray {
  val body = header.size + chunks.size + WEBP_FORM.size
  val out = ByteArray(RIFF_CHUNK_HEADER.toInt() + body)
  ascii("RIFF").copyInto(out)
  for (shift in 0 until 4) out[RIFF_SIZE_AT + shift] = (body ushr (8 * shift)).toByte()
  WEBP_FORM.copyInto(out, RIFF_CHUNK_HEADER.toInt())
  header.copyInto(out, WEBP_FIRST_CHUNK.toInt())
  chunks.copyInto(out, WEBP_FIRST_CHUNK.toInt() + header.size)
  return out
}

private fun writeU24(
  out: ByteArray,
  at: Int,
  value: Int,
) {
  out[at] = value.toByte()
  out[at + 1] = (value ushr 8).toByte()
  out[at + 2] = (value ushr 16).toByte()
}

private val ANIM = fourcc("ANIM")
private val ANMF = fourcc("ANMF")
private val ALPH = fourcc("ALPH")
private val VP8 = fourcc("VP8 ")
private val VP8L = fourcc("VP8L")
private val WEBP_FORM = ascii("WEBP")

private const val RIFF_SIZE_AT = 4
private const val CHUNK_TYPE_AT = 0
private const val CHUNK_SIZE_AT = 4
private const val ANIMATION_FLAG = 0x02
private const val ALPHA_FLAG = 0x10
private const val VP8X_SIZE = 10
private const val CANVAS_WIDTH_AT = 4
private const val CANVAS_HEIGHT_AT = 7
private const val ANIM_LOOPS_AT = 4
private const val ANMF_HEADER_SIZE = 16
private const val NO_BLEND_FLAG = 0x02
private const val DISPOSE_BACKGROUND_FLAG = 0x01
