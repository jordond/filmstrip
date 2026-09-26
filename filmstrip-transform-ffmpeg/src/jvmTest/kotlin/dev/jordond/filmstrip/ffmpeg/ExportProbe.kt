package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.ffmpeg.ExportConstants.AUDIO_SAMPLE_RATE
import dev.jordond.filmstrip.ffmpeg.ExportConstants.CLIP_BLUES
import dev.jordond.filmstrip.ffmpeg.ExportConstants.DRAWN_DIFFERENCE
import dev.jordond.filmstrip.ffmpeg.ExportConstants.ENVELOPE_WINDOW_SAMPLES
import dev.jordond.filmstrip.ffmpeg.ExportConstants.FRAME_LEAD
import dev.jordond.filmstrip.ffmpeg.ExportConstants.INDEXED_SIZE
import dev.jordond.filmstrip.ffmpeg.ExportConstants.INDEX_FLOOR
import dev.jordond.filmstrip.ffmpeg.ExportConstants.INDEX_STEP
import dev.jordond.filmstrip.ffmpeg.ExportConstants.MICROS_PER_SECOND
import dev.jordond.filmstrip.ffmpeg.ExportConstants.MIDDLE_CROP
import dev.jordond.filmstrip.ffmpeg.ExportConstants.PRIMARY_HZ
import dev.jordond.filmstrip.ffmpeg.ExportConstants.RGB_CHANNELS
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TONE_WINDOW_SAMPLES
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.CHANNEL_DRIFT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.ffmpeg.internal.formatSeconds
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.test.ToneAnalysis
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

// The readers the export suites share, which pull frames, pixels and samples out of a written file through ffmpeg.

internal fun luminance(pixel: Triple<Int, Int, Int>): Int = pixel.first + pixel.second + pixel.third

/**
 * The exported file's left channel, decoded by ffmpeg itself rather than by anything under test.
 */
internal fun decodedAudio(path: String): FloatArray {
  val process =
    ProcessBuilder(
      "ffmpeg",
      "-hide_banner",
      "-loglevel",
      "error",
      "-i",
      path,
      "-vn",
      "-filter:a",
      "aformat=sample_fmts=flt:sample_rates=$AUDIO_SAMPLE_RATE:channel_layouts=mono",
      "-f",
      "f32le",
      "-",
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
  val bytes = process.inputStream.readAllBytes()
  process.waitFor()

  val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
  return FloatArray(buffer.remaining()).also { buffer.get(it) }
}

/**
 * The level around [at], as the root mean square of the window centred on it.
 *
 * Root mean square rather than a peak because the fixture's tone runs at 440 Hz, and a window
 * short enough to follow a fade holds too few cycles for its largest sample to mean anything.
 */
internal fun FloatArray.levelAt(at: Duration): Float {
  val centre = (at.toDouble(DurationUnit.SECONDS) * AUDIO_SAMPLE_RATE).toInt()
  val half = ENVELOPE_WINDOW_SAMPLES / 2
  val from = (centre - half).coerceIn(0, size)
  val to = (centre + half).coerceIn(0, size)
  assertTrue(to > from, "the export carried no samples around $at")
  var sum = 0.0
  for (index in from until to) sum += this[index].toDouble() * this[index]
  return sqrt(sum / (to - from)).toFloat()
}

internal val ResolvedTrack.onlyClip: ResolvedClip get() = clips.single()

/**
 * How much of [frequencyHz] the window opening at [at] carries.
 *
 * Measured by the analysis every backend's mix test shares, so four readings of one bed cannot
 * disagree about what they measured.
 *
 * The window is centred on [at] rather than opened there, because a reading taken across a ramp
 * is the mean gain over the window and a linear ramp's mean sits at its middle. Anchoring the
 * window's start instead would read a fade high by half the window's own rise.
 */
internal fun FloatArray.toneAt(
  at: Duration,
  frequencyHz: Int,
): Float =
  ToneAnalysis.amplitudeOver(
    samples = this,
    from = (at.toDouble(DurationUnit.SECONDS) * AUDIO_SAMPLE_RATE).toInt() - TONE_WINDOW_SAMPLES / 2,
    length = TONE_WINDOW_SAMPLES,
    frequencyHz = frequencyHz,
    sampleRate = AUDIO_SAMPLE_RATE,
  )

/**
 * Asserts [frames] holds [slots] frames, each presented on its own slot of the grid at [frameRate].
 *
 * ffprobe prints a time to six decimals, so a slot whose time is not a whole number of microseconds reads a
 * microsecond either side of the floored one.
 */
internal fun assertOnTheGrid(
  frames: List<WrittenFrame>,
  slots: Long,
  frameRate: Int,
  what: String,
) {
  withClue(what) {
    frames.size shouldBe slots.toInt()
    frames.forEachIndexed { slot, frame ->
      val onSlot = outputSlotTime(slot.toLong(), frameRate)
      assertTrue(
        (frame.time - onSlot).absoluteValue <= 1.microseconds,
        "frame $slot is presented at ${frame.time}, where its slot sits at $onSlot",
      )
    }
  }
}

/**
 * Writes a clip of flat frames at [frameRate] for [length], with a tone under it for [sound] or no sound at all.
 *
 * Each frame's colour names it: [blue] says which clip it came from, and red and green count its own index in tens
 * and units, so a frame read back out of an export says which source frame it is without a pixel comparison.
 */
internal fun indexedClip(
  frameRate: Int,
  length: Duration,
  blue: Int,
  sound: Duration? = null,
): File {
  val target = File.createTempFile("filmstrip-grid-source", ".mp4")
  val seconds = length.toDouble(DurationUnit.SECONDS)
  val picture =
    "color=c=black:s=${INDEXED_SIZE.width}x${INDEXED_SIZE.height}:r=$frameRate:d=$seconds,format=gbrp," +
      "geq=r='$INDEX_FLOOR+mod(N\\,10)*$INDEX_STEP':g='$INDEX_FLOOR+trunc(N/10)*$INDEX_STEP':b=$blue"
  val command =
    buildList {
      addAll(listOf("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", picture))
      sound?.let {
        val tone = "sine=frequency=$PRIMARY_HZ:sample_rate=$AUDIO_SAMPLE_RATE:duration=${it.toDouble(
          DurationUnit.SECONDS,
        )}"
        addAll(listOf("-f", "lavfi", "-i", tone, "-c:a", "aac"))
      }
      addAll(listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", target.absolutePath))
    }
  val process = ProcessBuilder(command).redirectErrorStream(true).start()
  val log = process.inputStream.readAllBytes().decodeToString()
  assertTrue(process.waitFor() == 0, "ffmpeg could not write the indexed clip: $log")
  return target
}

/**
 * Every frame of [path] in presentation order, each averaged down to one colour, with the time it is presented at.
 *
 * Decoded and probed by ffmpeg itself rather than by anything under test, and passed through as decoded so no frame
 * is duplicated or dropped on the way out. [middleOnly] averages the middle sixteenth of each frame instead, clear of
 * any corner an overlay is drawn in and of the bars a fill paints.
 */
internal fun writtenFrames(
  path: String,
  middleOnly: Boolean = false,
  probe: Pair<Int, Int>? = null,
): List<WrittenFrame> {
  val crop =
    when {
      probe != null -> "crop=$PROBE_SIDE:$PROBE_SIDE:${probe.first}:${probe.second},"
      middleOnly -> "$MIDDLE_CROP,"
      else -> ""
    }
  val probe =
    ProcessBuilder(
      "ffprobe",
      "-v",
      "error",
      "-select_streams",
      "v:0",
      "-show_entries",
      "frame=pts_time",
      "-of",
      "csv=p=0",
      path,
    ).start()
  val times =
    probe
      .inputReader()
      .readText()
      .lineSequence()
      .mapNotNull { line ->
        line
          .trim()
          .trimEnd(',')
          .toDoubleOrNull()
          ?.let { (it * MICROS_PER_SECOND).roundToLong().microseconds }
      }.toList()
  probe.waitFor()

  val decode =
    ProcessBuilder(
      "ffmpeg",
      "-hide_banner",
      "-loglevel",
      "error",
      "-i",
      path,
      "-map",
      "0:v:0",
      "-fps_mode",
      "passthrough",
      "-vf",
      "${crop}scale=1:1:flags=area",
      "-f",
      "rawvideo",
      "-pix_fmt",
      "rgb24",
      "-",
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
  val colours = decode.inputStream.readAllBytes()
  decode.waitFor()

  val count = colours.size / RGB_CHANNELS
  assertTrue(count == times.size, "ffmpeg decoded $count frames of $path where ffprobe listed ${times.size}")
  return times.mapIndexed { index, time ->
    val base = index * RGB_CHANNELS
    WrittenFrame(
      time = time,
      red = colours[base].toInt() and 0xFF,
      green = colours[base + 1].toInt() and 0xFF,
      blue = colours[base + 2].toInt() and 0xFF,
    )
  }
}

/**
 * One written frame, averaged down to a single colour, and the time it is presented at.
 */
internal data class WrittenFrame(
  val time: Duration,
  val red: Int,
  val green: Int,
  val blue: Int,
) {
  // Which indexed clip wrote this frame and which of its frames it is, or null for a frame the fill wrote.
  val shown: Shown?
    get() {
      if (blue < INDEX_FLOOR) return null
      val units = ((red - INDEX_FLOOR).toFloat() / INDEX_STEP).roundToInt()
      val tens = ((green - INDEX_FLOOR).toFloat() / INDEX_STEP).roundToInt()
      val clip = CLIP_BLUES.minBy { abs(it - blue) }
      return Shown(clip, tens * 10 + units)
    }
}

/**
 * An indexed clip's frame: the clip by its blue, and the frame's own index inside it.
 */
internal data class Shown(
  val blue: Int,
  val index: Int,
)

/**
 * The RGB value of one pixel of the frame presented at [at], read by asking ffmpeg itself to crop
 * the frame down to it.
 *
 * Cropped to 2x2 rather than 1x1: `crop` rounds a size that is not a multiple of yuv420p's
 * chroma subsampling down to zero unless told otherwise, and only the first pixel is read back.
 *
 * [at] puts `-ss` ahead of `-i`, so the decoder opens on the frame rather than reading its way
 * to it. An overlay is drawn on every frame of its run, and a read that only ever opens the file
 * says nothing about the rest of them.
 *
 * Verified independently of the code under test, the same way [ffprobe] is.
 */
internal fun pixelAt(
  path: String,
  x: Int,
  y: Int,
  at: Duration = Duration.ZERO,
): Triple<Int, Int, Int> {
  val pixels = regionOf(path, x, y, side = 2, at = at)
  return Triple(pixels[0].toInt() and 0xFF, pixels[1].toInt() and 0xFF, pixels[2].toInt() and 0xFF)
}

/**
 * The mean RGB of a [side] by [side] square of the frame presented at [at].
 *
 * Averaged rather than sampled, because the fixtures carry seeded noise and a re-encode moves one
 * pixel by more than a step of the ramps these tests measure.
 */
internal fun regionAverage(
  path: String,
  x: Int,
  y: Int,
  side: Int,
  at: Duration,
): Triple<Double, Double, Double> {
  val pixels = regionOf(path, x, y, side, at)
  assertTrue(pixels.size >= side * side * RGB_CHANNELS, "ffmpeg read ${pixels.size} bytes at $at of $path")
  val sums = DoubleArray(RGB_CHANNELS)
  pixels.forEachIndexed { index, byte -> sums[index % RGB_CHANNELS] += (byte.toInt() and 0xFF).toDouble() }
  val count = (pixels.size / RGB_CHANNELS).toDouble()
  return Triple(sums[0] / count, sums[1] / count, sums[2] / count)
}

internal fun regionOf(
  path: String,
  x: Int,
  y: Int,
  side: Int,
  at: Duration,
): ByteArray {
  val seek = if (at > Duration.ZERO) listOf("-ss", formatSeconds(at.toDouble(DurationUnit.SECONDS))) else emptyList()
  val process =
    ProcessBuilder(
      listOf("ffmpeg", "-y", "-hide_banner", "-loglevel", "error") + seek +
        listOf(
          "-i",
          path,
          "-vf",
          "crop=$side:$side:$x:$y",
          "-vframes",
          "1",
          "-f",
          "rawvideo",
          "-pix_fmt",
          "rgb24",
          "-",
        ),
    ).start()
  val pixels = process.inputStream.readNBytes(side * side * RGB_CHANNELS)
  process.waitFor()
  return pixels
}

internal class ExportedOverlay(
  val file: File,
  val plan: ExportPlan,
)

internal fun assertReads(
  frames: List<WrittenFrame>,
  colour: Triple<Int, Int, Int>,
  what: String,
  drift: Int = CHANNEL_DRIFT,
) {
  assertTrue(frames.isNotEmpty(), "no frames read for $what")
  frames.forEachIndexed { index, frame ->
    val read = Triple(frame.red, frame.green, frame.blue)
    assertTrue(read.isNear(colour, drift), "$what read $read on frame $index, expected $colour")
  }
}

internal fun centreOf(
  rect: NormalizedRect,
  frame: Size,
): Pair<Int, Int> = pointIn(rect.left + rect.width / 2, rect.top + rect.height / 2, frame)

// Where a read has to open to land on one output frame. A read delivers the first frame at or
// after where it is asked for, so this sits inside the period the frame before it occupies.
internal fun opensOn(
  index: Int,
  plan: ExportPlan,
): Duration = frameTime(index, assertNotNull(plan.output.frameRate)) - FRAME_LEAD

internal fun frameTime(
  index: Int,
  rate: Int,
): Duration = (index.toDouble() / rate).seconds

// Where the shared sampler and the shared placement put the overlay at one instant, which is what
// the written file is read against rather than a rectangle this test works out for itself.
internal fun drawnRect(
  spec: ImageOverlay,
  frame: Size,
  span: TimeRange,
  at: Duration,
  image: Size = OVERLAY_SIZE,
): NormalizedRect =
  spec
    .placedOn(frame, image)
    .animatedBy(assertNotNull(spec.frameAt(at, span)))
    .rectOn(frame)

internal fun drawnProbe(
  spec: ImageOverlay,
  frame: Size,
  span: TimeRange,
  at: Duration,
  image: Size = OVERLAY_SIZE,
): Pair<Int, Int> = centreOf(drawnRect(spec, frame, span, at, image), frame)

// Rounded down to an even pixel, since crop on a subsampled frame refuses an odd offset.
internal fun pointIn(
  x: Float,
  y: Float,
  frame: Size,
): Pair<Int, Int> = (x * frame.width).toInt() / 2 * 2 to (y * frame.height).toInt() / 2 * 2

/**
 * Whether the overlay covers [probe] at [at], read as a difference against the same export with
 * no overlay on it.
 *
 * Comparing two exports rather than naming a colour is what keeps this honest wherever the
 * overlay lands: both files decode the same source frame, so everything but the overlay agrees.
 */
internal fun drawnAt(
  bare: ExportedOverlay,
  marked: ExportedOverlay,
  probe: Pair<Int, Int>,
  at: Duration,
): Boolean {
  val under = regionAverage(bare.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at)
  val over = regionAverage(marked.file.absolutePath, probe.first, probe.second, PROBE_SIDE, at)
  return under.toList().zip(over.toList()).maxOf { (a, b) -> abs(a - b) } > DRAWN_DIFFERENCE
}

internal fun ffprobe(path: String): String {
  val process =
    ProcessBuilder(
      "ffprobe",
      "-v",
      "error",
      "-show_entries",
      "stream=codec_type,codec_name,width,height,avg_frame_rate",
      "-of",
      "default=noprint_wrappers=1",
      path,
    ).start()
  val output = process.inputReader().readText()
  process.waitFor()

  val fields =
    output
      .lineSequence()
      .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
      .groupBy({ it[0].trim() }, { it[1].trim() })

  return "${fields["width"]!!.first()}x${fields["height"]!!.first()} " +
    "${fields["avg_frame_rate"]!!.first()} " +
    fields["codec_name"]!!.joinToString(" ")
}
