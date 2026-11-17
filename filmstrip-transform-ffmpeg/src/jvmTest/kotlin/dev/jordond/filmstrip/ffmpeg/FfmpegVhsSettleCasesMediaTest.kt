package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.tape.TapePictureParams
import dev.jordond.filmstrip.effects.filters.tape.TapePictureRamp
import dev.jordond.filmstrip.effects.filters.tape.TapePictureSignal
import dev.jordond.filmstrip.effects.filters.tape.TapeSettle
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.VHS_HEIGHT
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.VHS_ROWS_READ
import dev.jordond.filmstrip.ffmpeg.support.VHS_WIDTH
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.chromaPlanesOf
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.lumaPlaneOf
import dev.jordond.filmstrip.ffmpeg.support.rampAt
import dev.jordond.filmstrip.ffmpeg.support.tickOf
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.kotest.matchers.ints.shouldBeLessThan as shouldBeFewerThan

/**
 * A [Vhs] that settles, on ffmpeg's live planes and on its drawn graph, over every easing, from nothing and to nothing,
 * on a clip that plays faster or is trimmed, and on each pass of a looping track, read against the signal at the level
 * each frame has.
 */
class FfmpegVhsSettleCasesMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `every easing reads the signal and its two forms agree`() =
    runBlocking<Unit> {
      Easing.entries.forEach { easing ->
        val look = Vhs(intensity = 0.35f, tracking = Tracking.Events(), settle = TapeSettle(1f, SETTLE, easing))
        val composition = draw.composition(listOf(clip(), clip(listOf(look))))

        withClue("easing $easing") { assertCase(composition, look, OPENS, 4 * FILTER_RATE) }
      }
    }

  @Test
  fun `a settle from nothing starts on the picture as it is and a settle to nothing ends on it`() =
    runBlocking<Unit> {
      val up = Vhs(intensity = 0.8f, settle = TapeSettle(0f, SETTLE, Easing.EaseInOut))
      val down = Vhs(intensity = 0f, settle = TapeSettle(0.8f, SETTLE, Easing.EaseInOut))
      val plain = draw.formPlanes(draw.composition(listOf(clip(), clip())), 4 * FILTER_RATE, live = false)

      listOf(up to true, down to false).forEach { (look, opensClear) ->
        val composition = draw.composition(listOf(clip(), clip(listOf(look))))
        val drawn = assertCase(composition, look, OPENS, 4 * FILTER_RATE)
        val clear = if (opensClear) listOf(OPENS_SLOT) else listOf(OPENS_SLOT + FILTER_RATE, LAST_SLOT)

        withClue("$look") { clear.forEach { slot -> frameBytes(drawn, slot) shouldBe frameBytes(plain, slot) } }
      }
    }

  // The route a look takes depends on where its frame is clear: beside the picture when bars sit round a Crop, and
  // anywhere once an earlier look has softened those bars. Both end a settle to nothing on the frame without the look.
  @Test
  fun `a settle to nothing ends on the frame with no look over bars and after another look`() =
    runBlocking<Unit> {
      val crop = Crop(AspectRatio(16, 9), fit = Fit.Contain)
      val bright = doubleArrayOf(0.745, 0.725, 0.706)
      val down = Vhs(intensity = 0f, settle = TapeSettle(0.8f, SETTLE, Easing.EaseInOut))
      val frames = 2 * FILTER_RATE

      listOf(listOf(crop), listOf(crop, Vhs(intensity = 0.5f))).forEach { before ->
        val drawn: suspend (List<EffectSpec>) -> ByteArray = { effects ->
          val clip = draw.clipOf(BAR_SIDE, BAR_SIDE, { bright.copyOf() }, effects)
          draw.formPlanes(draw.composition(listOf(clip), fill = Fill.Solid(BAR_FILL)), frames, live = false)
        }
        val settled = drawn(before + down)
        val plain = drawn(before)

        withClue("${before.size} effects before the look") {
          (FILTER_RATE until frames).forEach { slot -> frameBytes(settled, slot) shouldBe frameBytes(plain, slot) }
        }
      }
    }

  // The band's own paths while the level moves: its colour loss and snow weight in the drop map, its slide, its noise
  // and its snow, on a band that rolls and on an event band that runs for the whole settle.
  @Test
  fun `a settle with a band running reads the signal and its two forms agree`() =
    runBlocking<Unit> {
      val seed = (0 until SEED_SEARCH).first { seed -> bandAt(seed, 150) > MIDDLE && bandAt(seed, 130) > MIDDLE }
      val bands =
        listOf(
          Vhs(
            intensity = 0.35f,
            tracking = Tracking.Rolling(3.seconds, height = 0.3f, snow = 0.5f),
            settle = BAND_SETTLE,
          ),
          Vhs(
            intensity = 0.35f,
            seed = seed,
            tracking = Tracking.Events(1f, height = 0.3f, snow = 0.5f),
            settle = BAND_SETTLE,
          ),
        )

      bands.forEach { look ->
        val composition = draw.composition(listOf(clip(), clip(listOf(look))))

        withClue("${look.tracking}") { assertCase(composition, look, OPENS, 4 * FILTER_RATE, bandRows = true) }
      }
    }

  @Test
  fun `a clip that plays at twice the speed settles over its own slot`() =
    runBlocking<Unit> {
      val look = Vhs(intensity = 0.35f, settle = TapeSettle(1f, 500.milliseconds, Easing.EaseInOut))
      val fast = Clip(clip().source, speed = 2.0, effects = listOf(look))

      assertCase(draw.composition(listOf(clip(), fast)), look, OPENS, 3 * FILTER_RATE)
    }

  @Test
  fun `a trimmed clip settles from the first frame of its trim`() =
    runBlocking<Unit> {
      val look = Vhs(intensity = 0.35f, settle = TapeSettle(1f, 500.milliseconds, Easing.EaseInOut))
      val trimmed = Clip(clip().source, trim = TimeRange(500.milliseconds, 1500.milliseconds), effects = listOf(look))

      assertCase(draw.composition(listOf(clip(), trimmed)), look, OPENS, 3 * FILTER_RATE)
    }

  // A looping track lays no graph the planes helpers read and no preview, so its passes are read off an export. A
  // settle from nothing opens each pass on the picture as it is, which an encoder keeps to a few codes, and has left it
  // by the middle of the pass, which the look's noise and blur show by many more.
  @Test
  fun `each pass of a looping track settles again from its own first frame`() =
    runBlocking<Unit> {
      val look = Vhs(intensity = 0.8f, settle = TapeSettle(0f, 500.milliseconds, Easing.EaseInOut))
      val bed = File.createTempFile("settle-bed", ".wav").apply { deleteOnExit() }
      ProcessBuilder(hostFfmpeg, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=d=3", bed.path)
        .inheritIO()
        .start()
        .waitFor() shouldBe 0
      val pass = clip(seconds = 1).source
      val looped = { effects: List<Vhs> ->
        Composition(
          listOf(
            Track(listOf(Clip(pass, effects = effects)), looping = true),
            Track(listOf(Clip(MediaSource.of(bed.path))), content = TrackContent.Audio),
          ),
        )
      }
      val slots =
        (0 until 3).flatMap { lap ->
          listOf(0, FILTER_RATE / 2, FILTER_RATE - 1).map { lap * FILTER_RATE + it }
        }
      val settling = draw.exportedFrames(looped(listOf(look)), slots, ExportSpec())
      val plain = draw.exportedFrames(looped(emptyList()), slots, ExportSpec())
      val gaps = slots.indices.map { meanGap(settling[it], plain[it]) }

      (0 until 3).forEach { lap ->
        withClue("lap $lap, gaps ${gaps.slice(3 * lap..3 * lap + 2)}") {
          gaps[3 * lap] shouldBeLessThan OPENING_GAP
          gaps[3 * lap + 1] shouldBeGreaterThan 3 * OPENING_GAP
          gaps[3 * lap + 2] shouldBeGreaterThan 3 * OPENING_GAP
        }
      }
    }

  // Each frame's noise is its own, so two frames of a look differ by their noise wherever they sit. The step into the
  // end of a settle has to be no bigger than that, which is the frame before the end against the end for a settle,
  // and for the constant look it ends on.
  @Test
  fun `at 1080p the frame before the end of a settle is no further from the end than two frames of the look are`() =
    runBlocking<Unit> {
      val settling = Vhs(intensity = 0.35f, settle = TapeSettle(1f, SETTLE, Easing.EaseInOut))
      val constant = Vhs(intensity = 0.35f)
      val gap: suspend (Vhs) -> Double = { look ->
        val clip = draw.clipOf(HD_WIDTH, HD_HEIGHT, ::rampAt, listOf(look), seconds = 2)
        val frames = draw.formPlanes(draw.composition(listOf(clip)), 2, live = false, at = BEFORE_END)
        val lumaBytes = HD_WIDTH * HD_HEIGHT
        meanGap(frames.copyOfRange(0, lumaBytes), frames.copyOfRange(frames.size / 2, frames.size / 2 + lumaBytes))
      }
      val step = gap(settling)
      val natural = gap(constant)

      withClue("the step into the end of the settle $step codes, two frames of the constant look $natural") {
        step shouldBeLessThan natural + STEP_ALLOWANCE
      }
    }

  // The two forms meet byte for byte but for a rounding edge: an expression runs in double where the planes pace the
  // level through a float, so a sample of tens of millions can land one code off, and none lands further.
  private fun assertFormsAgree(
    drawn: ByteArray,
    planes: ByteArray,
  ) {
    val off = drawn.indices.filter { drawn[it] != planes[it] }

    withClue("${off.size} of ${drawn.size} samples differ") {
      off.size shouldBeFewerThan ROUNDING_EDGES
      off.all { abs((drawn[it].toInt() and 0xFF) - (planes[it].toInt() and 0xFF)) <= 1 } shouldBe true
    }
  }

  private fun bandAt(
    seed: Int,
    tick: Int,
  ) = TapePictureSignal
    .trackingBand(
      seed,
      tick,
      TapePictureParams.of(Vhs(intensity = 0.35f, seed = seed, tracking = Tracking.Events(1f))),
    ).strength

  private fun meanGap(
    a: ByteArray,
    b: ByteArray,
  ): Double = a.indices.sumOf { abs((a[it].toInt() and 0xFF) - (b[it].toInt() and 0xFF)) }.toDouble() / a.size

  // The two forms agree byte for byte on every frame, and the frames read the signal at the level each has. Returns the
  // drawn frames.
  private suspend fun assertCase(
    composition: Composition,
    look: Vhs,
    opens: Duration,
    frames: Int,
    bandRows: Boolean = false,
  ): ByteArray {
    val drawn = draw.formPlanes(composition, frames, live = false)
    val planes = draw.formPlanes(composition, frames, live = true)
    val ramp = TapePictureRamp.of(look, TimeRange(opens)).shouldNotBeNull()
    val first = (opens.inWholeMilliseconds * FILTER_RATE / 1000).toInt()
    val overSlots = (ramp.endTick - ramp.startTick) / 2

    assertFormsAgree(drawn, planes)
    listOf(first, first + 1, first + overSlots / 2, first + overSlots, first + 2 * overSlots, first + 2 * overSlots + 5)
      .filter { it < frames }
      .forEach { slot ->
        withClue("slot $slot") { assertReadsSignal(frameOf(drawn, slot), slot, look, ramp, opens, bandRows) }
      }
    return drawn
  }

  // The luma and the chroma of every sample read against the signal at the level of the frame's tick.
  private fun assertReadsSignal(
    frame: Yuv420Frame,
    slot: Int,
    look: Vhs,
    ramp: TapePictureRamp,
    opens: Duration,
    bandRows: Boolean = false,
  ) {
    val tick = tickOf(slot)
    val params = look.pictureParams(ramp.levelAt(tick), opens)
    val every = (0 until VHS_HEIGHT step VHS_HEIGHT / VHS_ROWS_READ) + (VHS_HEIGHT - 1)
    val rows =
      if (bandRows) {
        // The rows the band sits on are the ones that tell, so a read of only those fails a band that is not drawn.
        every.filter {
          TapePictureSignal.trackingWeight(look.seed, tick, TapePictureSignal.virtualRow(it, VHS_HEIGHT), params) >
            0.0
        }
      } else {
        every
      }
    if (bandRows) withClue("slot $slot has no band row") { rows.isNotEmpty() shouldBe true }
    val columns = (VHS_WIDTH / READ_COLUMNS until VHS_WIDTH step VHS_WIDTH / READ_COLUMNS)
    rows.forEach { y ->
      columns.forEach { column ->
        val luma = lumaPlaneOf(look, tick, column, y, ::rampAt, params = params)
        withClue("row $y column $column") { abs(frame.luma(column, y) - luma) shouldBeLessThan VHS_LUMA_CODES }
      }
      (CHROMA_STEP until VHS_WIDTH / 2 step CHROMA_STEP).forEach { column ->
        val want = chromaPlanesOf(look, tick, column, y / 2, ::rampAt, params)
        withClue("chroma row ${y / 2} column $column") {
          abs(frame.cb(column, y / 2) - want[0]) shouldBeLessThan VHS_CHROMA_CODES
          abs(frame.cr(column, y / 2) - want[1]) shouldBeLessThan VHS_CHROMA_CODES
        }
      }
    }
  }

  private fun clip(
    effects: List<Vhs> = emptyList(),
    seconds: Int = 2,
  ) = draw.clipOf(VHS_WIDTH, VHS_HEIGHT, ::rampAt, effects, seconds = seconds)

  private fun frameBytes(
    frames: ByteArray,
    slot: Int,
  ): List<Byte> {
    val bytes = VHS_WIDTH * VHS_HEIGHT * 3 / 2
    return frames.copyOfRange(bytes * slot, bytes * (slot + 1)).toList()
  }

  private fun frameOf(
    frames: ByteArray,
    slot: Int,
  ): Yuv420Frame {
    val bytes = VHS_WIDTH * VHS_HEIGHT * 3 / 2
    return Yuv420Frame(VHS_WIDTH, VHS_HEIGHT, frames.copyOfRange(bytes * slot, bytes * (slot + 1)))
  }

  private companion object {
    val SETTLE = 1.seconds
    const val BAR_SIDE = 360
    const val BAR_FILL = 0xFF20C040.toInt()
    val OPENS = 2.seconds
    const val OPENS_SLOT = 2 * FILTER_RATE
    const val LAST_SLOT = 4 * FILTER_RATE - 1
    const val OPENING_GAP = 1.0
    const val SEED_SEARCH = 400
    const val ROUNDING_EDGES = 8
    const val MIDDLE = 0.5
    val BAND_SETTLE = TapeSettle(1f, SETTLE, Easing.EaseInOut)
    const val HD_WIDTH = 1920
    const val HD_HEIGHT = 1080
    val BEFORE_END = ((FILTER_RATE - 1).toDouble() / FILTER_RATE).seconds

    // Mean luma codes of allowance on top of what two frames of a look differ by, which measured 0.36 for both, so a
    // step the settle adds would show as a gap of an eighth of that.
    const val STEP_ALLOWANCE = 0.05
    const val READ_COLUMNS = 16
    const val CHROMA_STEP = 40
  }
}
