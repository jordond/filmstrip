package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.geometry.Size
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.time.Duration

// The display from [from] until [until] as an animated PNG starting at zero, one frame a state.
//
// ffmpeg's APNG demuxer counts time in units of 10 microseconds and rescales each delay onto them alone, so a delay is
// written over 50000, which lands two units for every state clock tick, or over 1 for whole seconds. A state longer
// than a second is a whole second frame and the remainder after it, which repeat one pixel the state already holds.
//
// Every frame count is hidden, for the loops osdFrameLoops makes of them to be laid under it.
internal fun osdAnimation(
  osd: CamcorderOsd,
  frame: Size,
  from: Duration,
  until: Duration,
): ByteArray {
  val changes = osd.changesWithin(from, until, withFrames = false)
  val delaysOf = changes.indices.map { framesFor(changes, it, until) }
  val writer = ApngWriter(frame, delaysOf.sumOf { it.size })
  var previous: OsdState? = null
  var previousBounds: List<PixelRect> = emptyList()
  changes.forEachIndexed { index, change ->
    val bounds = osd.lineBounds(change.state, frame.width, frame.height)
    val region =
      if (previous == null) {
        PixelRect(0, 0, frame.width, frame.height)
      } else {
        changedRegion(checkNotNull(previous), previousBounds, change.state, bounds) ?: CORNER
      }
    val delays = delaysOf[index]
    writer.frame(region, osd.rasterOf(change.state, frame.width, frame.height, region).rgbaBytes(), delays.first())
    val corner = osd.rasterOf(change.state, frame.width, frame.height, CORNER).rgbaBytes()
    delays.drop(1).forEach { writer.frame(CORNER, corner, it) }
    previous = change.state
    previousBounds = bounds
  }
  return writer.finish()
}

// The delays one state's frames carry, from when it starts until the next one does or [until], counted from zero.
private fun framesFor(
  changes: List<OsdChange>,
  index: Int,
  until: Duration,
): List<Delay> {
  val start = if (index == 0) 0L else ticksOf(changes[index].at)
  val end = changes.getOrNull(index + 1)?.let { ticksOf(it.at) } ?: maxOf(ceilTicksOf(until), start + 1)
  var left = (end - start).coerceAtLeast(1)
  val delays = mutableListOf<Delay>()
  while (left >= TICKS_PER_SECOND) {
    val seconds = minOf(left / TICKS_PER_SECOND, MAX_DELAY)
    delays += Delay(seconds.toInt(), 1)
    left -= seconds * TICKS_PER_SECOND
  }
  if (left > 0) delays += Delay(left.toInt(), TICKS_PER_SECOND.toInt())
  return delays
}

private fun ticksOf(time: Duration): Long = time.inWholeMicroseconds / OsdParams.STATE_CLOCK_MICROS

private fun ceilTicksOf(time: Duration): Long =
  (time.inWholeMicroseconds + OsdParams.STATE_CLOCK_MICROS - 1) / OsdParams.STATE_CLOCK_MICROS

internal class Delay(
  val numerator: Int,
  val denominator: Int,
)

// Writes an APNG frame by frame, every frame replacing its region and leaving the rest as it was.
internal class ApngWriter(
  private val size: Size,
  frames: Int,
) {
  private val out = ByteArrayOutputStream()
  private var sequence = 0
  private var first = true

  init {
    require(TICKS_PER_SECOND <= MAX_DELAY) {
      "An APNG delay is a 16 bit fraction, so $TICKS_PER_SECOND ticks a second do not fit its denominator."
    }
    out.write(SIGNATURE)
    chunk("IHDR") {
      writeInt(size.width)
      writeInt(size.height)
      writeByte(BIT_DEPTH)
      writeByte(COLOUR_RGBA)
      writeByte(0)
      writeByte(0)
      writeByte(0)
    }
    chunk("acTL") {
      writeInt(frames)
      writeInt(1)
    }
  }

  fun frame(
    region: PixelRect,
    rgba: ByteArray,
    delay: Delay,
  ) {
    chunk("fcTL") {
      writeInt(sequence++)
      writeInt(region.width)
      writeInt(region.height)
      writeInt(region.left)
      writeInt(region.top)
      writeShort(delay.numerator)
      writeShort(delay.denominator)
      writeByte(DISPOSE_NONE)
      writeByte(BLEND_SOURCE)
    }
    val data = deflated(region.width, rgba)
    if (first) {
      chunk("IDAT") { write(data) }
      first = false
    } else {
      chunk("fdAT") {
        writeInt(sequence++)
        write(data)
      }
    }
  }

  fun finish(): ByteArray {
    chunk("IEND") {}
    return out.toByteArray()
  }

  private fun deflated(
    width: Int,
    rgba: ByteArray,
  ): ByteArray {
    val stride = width * RGBA
    val bytes = ByteArrayOutputStream()
    DeflaterOutputStream(bytes).use { zip ->
      for (row in 0 until rgba.size / stride) {
        zip.write(FILTER_NONE)
        zip.write(rgba, row * stride, stride)
      }
    }
    return bytes.toByteArray()
  }

  private fun chunk(
    type: String,
    body: DataOutputStream.() -> Unit,
  ) {
    val content = ByteArrayOutputStream()
    DataOutputStream(content).apply(body).flush()
    val typed = type.encodeToByteArray() + content.toByteArray()
    DataOutputStream(out)
      .apply {
        writeInt(content.size())
        write(typed)
        writeInt(CRC32().apply { update(typed) }.value.toInt())
      }.flush()
  }
}

private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
private val CORNER = PixelRect(0, 0, 1, 1)
internal const val TICKS_PER_SECOND = 1_000_000L / OsdParams.STATE_CLOCK_MICROS
private const val MAX_DELAY = 65_535L
private const val BIT_DEPTH = 8
private const val COLOUR_RGBA = 6
private const val DISPOSE_NONE = 0
private const val BLEND_SOURCE = 0
private const val FILTER_NONE = 0
private const val RGBA = 4
