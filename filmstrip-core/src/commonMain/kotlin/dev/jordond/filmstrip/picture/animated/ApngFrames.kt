package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed
import dev.jordond.filmstrip.picture.png.PNG_IDAT
import dev.jordond.filmstrip.picture.png.PNG_IEND
import dev.jordond.filmstrip.picture.png.PNG_IHDR
import dev.jordond.filmstrip.picture.png.PNG_SIGNATURE
import dev.jordond.filmstrip.picture.png.PngChunk
import dev.jordond.filmstrip.picture.png.crcHolds
import dev.jordond.filmstrip.picture.png.pngChunk
import dev.jordond.filmstrip.picture.png.pngSamples
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Reads an APNG into its frames, rebuilding each as a standalone PNG and decoding it with `pngSamples`.
 *
 * A file with no `acTL` ahead of its image data, or with one frame, is a still. The default image is left out when it
 * has no `fcTL`, since then it is not a frame. Every chunk ahead of the image data, such as the palette and the color
 * chunks, is carried into each frame's PNG. `IHDR`, `acTL`, `fcTL`, `fdAT` and image data must pass their CRC, their
 * sequence numbers must run from zero without a gap, the frames must number what `acTL` says and the file must reach
 * `IEND`.
 */
internal fun ByteView.readApng(ceiling: Long): AnimatedRead {
  val parts = ApngParts()
  try {
    walk(parts)
  } catch (_: MalformedPicture) {
    return if (parts.animation != null) AnimatedRead.Refused(ANIMATED_UNREADABLE) else AnimatedRead.Still
  }
  val animation = parts.animation
  if (animation == null || animation.frames <= 1) return AnimatedRead.Still
  return try {
    if (parts.frames.size != animation.frames) malformed()
    val canvas = Size(u32(parts.header.start).toInt(), u32(parts.header.start + 4).toInt())
    parts.frames.forEachIndexed { index, frame ->
      if (!frame.region.liesWithin(canvas)) return AnimatedRead.Refused(animatedFrameOutside(index))
    }
    val held =
      parts.frames.sumOf {
        it.region.size.width
          .toLong() * it.region.size.height * SAMPLE_BYTES
      }
    if (held > ceiling) return AnimatedRead.Refused(animatedPictureTooLarge(held, ceiling))
    val stored = parts.frames.map { it.decoded(this, parts, ceiling) }
    val delays = parts.frames.map { shownFrameDelay(PictureContainer.Png, it.delay) }
    val plays = framePlays(PictureContainer.Png, animation.plays)
    AnimatedRead.Animated(AnimatedPicture(canvas, stored, FrameTimeline(delays, plays)))
  } catch (_: MalformedPicture) {
    AnimatedRead.Refused(ANIMATED_UNREADABLE)
  }
}

private class Animation(
  val frames: Int,
  val plays: Int,
)

private class Span(
  val start: Int,
  val end: Int,
)

private class ApngFrame(
  val region: PictureCrop,
  val delay: Duration,
  val disposal: FrameDisposal,
  val blend: FrameBlend,
) {
  val data = ArrayList<Span>()

  fun decoded(
    file: ByteView,
    parts: ApngParts,
    ceiling: Long,
  ): StoredFrame {
    if (data.isEmpty()) malformed()
    val png = standalone(file, parts, this)
    val samples = pngSamples(png, ceiling) ?: malformed()
    if (samples.width != region.size.width || samples.height != region.size.height) malformed()
    return StoredFrame(region, blend, disposal, FramePixels.Samples(samples.rgba, samples.sixteenBit))
  }
}

private class ApngParts {
  var header: PngChunk = PngChunk(0, 0, 0)
  var animation: Animation? = null
  val carried = ArrayList<PngChunk>()
  val frames = ArrayList<ApngFrame>()
}

private fun ByteView.walk(parts: ApngParts) {
  var at = PNG_SIGNATURE.size
  var sequence = 0L
  var sawImage = false
  var imageIsFrame = false
  var headerSeen = false
  while (true) {
    val chunk = pngChunk(at)
    at = chunk.next
    when (chunk.type) {
      PNG_IHDR -> {
        if (headerSeen || chunk.size != HEADER_SIZE || !crcHolds(chunk)) malformed()
        headerSeen = true
        parts.header = chunk
      }
      ACTL -> {
        if (!headerSeen || chunk.size != ACTL_SIZE || !crcHolds(chunk)) malformed()
        if (!sawImage) parts.animation = Animation(frameCount(u32(chunk.start)), playsOf(u32(chunk.start + 4)))
      }
      FCTL -> {
        if (!sawImage && parts.animation == null) malformed()
        if (chunk.size != FCTL_SIZE || !crcHolds(chunk) || u32(chunk.start) != sequence++) malformed()
        parts.frames += frameOf(chunk)
      }
      PNG_IDAT -> {
        if (!crcHolds(chunk)) malformed()
        if (!sawImage) {
          sawImage = true
          imageIsFrame = parts.frames.isNotEmpty()
        }
        if (imageIsFrame) parts.frames[0].data += Span(chunk.start, chunk.end)
      }
      FDAT -> {
        if (chunk.size < SEQUENCE_SIZE || !crcHolds(chunk) || u32(chunk.start) != sequence++) malformed()
        if (parts.frames.isEmpty()) malformed()
        parts.frames.last().data += Span(chunk.start + SEQUENCE_SIZE, chunk.end)
      }
      PNG_IEND -> {
        return
      }
      else -> {
        val intact = crcHolds(chunk)
        if (chunk.isCritical && (!intact || sawImage)) malformed()
        if (intact && !sawImage) parts.carried += chunk
      }
    }
  }
}

private fun frameCount(value: Long): Int = if (value in 0..MAX_FRAMES) value.toInt() else malformed()

private fun playsOf(value: Long): Int = minOf(value, Int.MAX_VALUE.toLong()).toInt()

private fun ByteView.frameOf(chunk: PngChunk): ApngFrame {
  val start = chunk.start
  val dimensions = listOf(4, 8, 12, 16).map { u32(start + it) }
  if (dimensions.any { it > Int.MAX_VALUE }) malformed()
  val numerator = u16(start + DELAY_NUMERATOR_AT).toLong()
  val denominator = u16(start + DELAY_DENOMINATOR_AT).let { if (it == 0) DEFAULT_DENOMINATOR else it }
  val disposal =
    when (u8(start + DISPOSE_AT)) {
      0 -> FrameDisposal.None
      1 -> FrameDisposal.Background
      2 -> FrameDisposal.Previous
      else -> malformed()
    }
  val blend =
    when (u8(start + BLEND_AT)) {
      0 -> FrameBlend.Source
      1 -> FrameBlend.Over
      else -> malformed()
    }
  val region =
    PictureCrop(dimensions[2].toInt(), dimensions[3].toInt(), Size(dimensions[0].toInt(), dimensions[1].toInt()))
  return ApngFrame(region, (numerator * NANOS_PER_SECOND / denominator).nanoseconds, disposal, blend)
}

// The PNG a frame is on its own, the header sized to the frame, the chunks carried from ahead of the image data and the
// frame's data as image data.
private fun standalone(
  file: ByteView,
  parts: ApngParts,
  frame: ApngFrame,
): ByteArray {
  val carriedSize = parts.carried.sumOf { CHUNK_OVERHEAD + it.size }
  val dataSize = frame.data.sumOf { CHUNK_OVERHEAD + it.end - it.start }
  val out = ByteArray(PNG_SIGNATURE.size + CHUNK_OVERHEAD + HEADER_SIZE + carriedSize + dataSize + CHUNK_OVERHEAD)
  PNG_SIGNATURE.copyInto(out)
  var at = PNG_SIGNATURE.size
  val header = file.bytes.copyOfRange(parts.header.start, parts.header.end)
  writeInt(header, 0, frame.region.size.width)
  writeInt(header, 4, frame.region.size.height)
  at = writeChunk(out, at, PNG_IHDR, header, 0, header.size)
  for (chunk in parts.carried) at = writeChunk(out, at, chunk.type, file.bytes, chunk.start, chunk.end)
  for (span in frame.data) at = writeChunk(out, at, PNG_IDAT, file.bytes, span.start, span.end)
  writeChunk(out, at, PNG_IEND, out, 0, 0)
  return out
}

private fun writeChunk(
  out: ByteArray,
  at: Int,
  type: Int,
  source: ByteArray,
  start: Int,
  end: Int,
): Int {
  val length = end - start
  writeInt(out, at, length)
  writeInt(out, at + 4, type)
  source.copyInto(out, at + 8, start, end)
  val crc = ByteView(out, out.size).crc32(at + 4, at + 8 + length)
  writeInt(out, at + 8 + length, crc.toInt())
  return at + CHUNK_OVERHEAD + length
}

private fun writeInt(
  out: ByteArray,
  at: Int,
  value: Int,
) {
  out[at] = (value ushr 24).toByte()
  out[at + 1] = (value ushr 16).toByte()
  out[at + 2] = (value ushr 8).toByte()
  out[at + 3] = value.toByte()
}

private val ACTL = fourcc("acTL")
private val FCTL = fourcc("fcTL")
private val FDAT = fourcc("fdAT")

private const val HEADER_SIZE = 13
private const val ACTL_SIZE = 8
private const val FCTL_SIZE = 26
private const val SEQUENCE_SIZE = 4
private const val CHUNK_OVERHEAD = 12
private const val DELAY_NUMERATOR_AT = 20
private const val DELAY_DENOMINATOR_AT = 22
private const val DISPOSE_AT = 24
private const val BLEND_AT = 25
private const val DEFAULT_DENOMINATOR = 100
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val MAX_FRAMES = 1L shl 20
private const val SAMPLE_BYTES = 8L
