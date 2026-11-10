package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderOsd
import dev.jordond.filmstrip.effects.filters.camcorder.OsdElement
import dev.jordond.filmstrip.effects.filters.camcorder.OsdRaster
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * A [Camcorder] drawn through the real browser compositor, read against [CamcorderOsd.rasterAt].
 *
 * Reads come off a preview, which is the export's own lowering and pass with no encoder in the way. A pixel the display
 * leaves clear is held against the same clip drawn without it.
 */
class BrowserCamcorderMediaTest {
  @Test
  fun everyLitAndEdgePixelLandsOnTheRaster() =
    runTest {
      val clip = rampClip()

      val frame = drawn(edit(clip, listOf(CAMCORDER)))
      val picture = drawn(edit(clip))

      assertOnRaster(frame, picture, OSD.rasterAt(READ_TIME, WIDTH, HEIGHT))
    }

  @Test
  fun aFrameEachSideOfASecondShowsItsOwnDisplay() =
    runTest {
      val clip = rampClip()
      val preview = previewOf(edit(clip, listOf(CAMCORDER)), SPEC)
      val picture = drawn(edit(clip), time = BEFORE_SECOND)
      try {
        val before = preview.frameAt(BEFORE_SECOND).shouldNotBeNull()
        val after = preview.frameAt(AFTER_SECOND).shouldNotBeNull()
        val beforeRaster = OSD.rasterAt(BEFORE_SECOND, WIDTH, HEIGHT)
        val afterRaster = OSD.rasterAt(AFTER_SECOND, WIDTH, HEIGHT)

        withClue("the counter has to change across the second for this to tell") {
          differing(beforeRaster, afterRaster) shouldBeGreaterThan 0
        }
        assertOnRaster(before, picture, beforeRaster)
        assertOnRaster(after, picture, afterRaster)
      } finally {
        preview.release()
      }
    }

  @Test
  fun aFrameCountMidSecondLandsOnTheRaster() =
    runTest {
      val clip = rampClip()
      val counting = CamcorderOsd(COUNTING, FRAME_RATE.toFloat())
      val raster = counting.rasterAt(READ_TIME, WIDTH, HEIGHT)

      withClue("the count has to differ from the frame before for this to tell") {
        differing(raster, counting.rasterAt(outputSlotTime(5, FRAME_RATE), WIDTH, HEIGHT)) shouldBeGreaterThan 0
      }
      assertOnRaster(drawn(edit(clip, listOf(COUNTING))), drawn(edit(clip)), raster)
    }

  @Test
  fun aSmallerRenderIsTheRasterAtItsOwnSize() =
    runTest {
      val clip = rampClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(clip, listOf(CAMCORDER)), small)
      val picture = drawn(edit(clip), small)

      frame.size.height shouldBe SMALL_HEIGHT
      assertOnRaster(frame, picture, OSD.rasterAt(READ_TIME, frame.size.width, SMALL_HEIGHT))
    }

  @Test
  fun aDisplayAheadOfVhsIsRecordedOnTheTape() =
    runTest {
      val clip = rampClip()
      val vhs = Vhs(intensity = MID)

      val frame = drawn(edit(clip, listOf(CAMCORDER, vhs)))
      val recorded = drawn(edit(clip, listOf(CAMCORDER)))
      val raster = OSD.rasterAt(READ_TIME, WIDTH, HEIGHT)
      val params = VhsParams.of(vhs)
      val tick = VhsSignal.tickAt(READ_TIME.toDouble(DurationUnit.SECONDS))
      var worst = 0.0
      var moved = 0

      textRows(raster).forEach { y ->
        val row = VhsSignal.virtualRow(y, HEIGHT)
        for (column in 0 until WIDTH) {
          val x = (column + 0.5) / WIDTH
          val expected =
            VhsSignal.pixelAt(x, row, tick, vhs.seed, WIDTH, params) { column ->
              DoubleArray(RGB) {
                recorded.codesAt(column, y)[it] /
                  FULL
              }
            }
          val read = frame.codesAt(column, y)
          for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - expected[channel] * FULL))
          val argb = raster.argbAt(column, y)
          if (argb != CLEAR && read.gapTo(argb) > MOVED_CODES) moved++
        }
      }
      worst shouldBeLessThan SIGNAL_CODES
      withClue("the tape moved the display off its own codes") { moved shouldBeGreaterThan 0 }
    }

  @Test
  fun aDisplayAfterVhsStaysClean() =
    runTest {
      val frame = drawn(edit(rampClip(), listOf(Vhs(intensity = MID), CAMCORDER)))
      val raster = OSD.rasterAt(READ_TIME, WIDTH, HEIGHT)
      var lit = 0

      for (y in 0 until HEIGHT) {
        for (x in 0 until WIDTH) {
          val argb = raster.argbAt(x, y)
          if (argb == CLEAR) continue
          lit++
          withClue("($x, $y)") { frame.codesAt(x, y).gapTo(argb) shouldBeLessThan EXACT_CODES }
        }
      }
      lit shouldBeGreaterThan 0
    }

  private fun assertOnRaster(
    frame: PreviewFrame,
    picture: PreviewFrame,
    raster: OsdRaster,
  ) {
    var lit = 0
    for (y in 0 until raster.height) {
      for (x in 0 until raster.width) {
        val argb = raster.argbAt(x, y)
        val read = frame.codesAt(x, y)
        if (argb == CLEAR) {
          val expected = picture.codesAt(x, y)
          withClue("clear ($x, $y)") {
            (0 until RGB).maxOf { abs(read[it] - expected[it]) } shouldBeLessThan EXACT_CODES
          }
        } else {
          lit++
          withClue("lit ($x, $y)") { read.gapTo(argb) shouldBeLessThan EXACT_CODES }
        }
      }
    }
    lit shouldBeGreaterThan 0
  }

  private fun differing(
    first: OsdRaster,
    second: OsdRaster,
  ): Int {
    var count = 0
    for (y in 0 until first.height) {
      for (x in 0 until first.width) if (first.argbAt(x, y) != second.argbAt(x, y)) count++
    }
    return count
  }

  // A row near the top, the middle and the bottom of the display's lines.
  private fun textRows(raster: OsdRaster): List<Int> {
    val rows = (0 until raster.height).filter { y -> (0 until raster.width).any { raster.argbAt(it, y) != CLEAR } }
    return listOf(rows.first() + 2, rows[rows.size / 2], rows.last() - 2)
  }

  private suspend fun drawn(
    composition: Composition,
    spec: ExportSpec = SPEC,
    time: Duration = READ_TIME,
  ): PreviewFrame {
    val preview = previewOf(composition, spec)
    try {
      return preview.frameAt(time).shouldNotBeNull()
    } finally {
      preview.release()
    }
  }

  private suspend fun previewOf(
    composition: Composition,
    spec: ExportSpec,
  ): BrowserPreview =
    webCodecsExportEngine(
      components =
        ComponentRegistry
          .Builder()
          .addEffectResolver(BuiltInEffectResolver())
          .addEffectResolver(FilterEffectResolver())
          .build(),
      prober = BrowserProber(),
    ).resolve(composition, spec)
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition
      .toBrowserPreview(composition)

  private fun edit(
    clip: MediaSource,
    effects: List<EffectSpec> = emptyList(),
  ) = Composition(tracks = listOf(Track(listOf(Clip(clip)))), effects = effects, audio = AudioSpec.Remove)

  private suspend fun rampClip(): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, x, _ ->
        val step = x * RAMP_CODES / WIDTH
        Rgb(RAMP_FLOOR + step, RAMP_MIDDLE, RAMP_FLOOR + RAMP_CODES - step)
      },
    )

  private fun PreviewFrame.codesAt(
    x: Int,
    y: Int,
  ): DoubleArray {
    val rgb = at((x + 0.5) / size.width, (y + 0.5) / size.height)
    return doubleArrayOf(rgb.red.toDouble(), rgb.green.toDouble(), rgb.blue.toDouble())
  }

  private fun DoubleArray.gapTo(argb: Int): Double =
    maxOf(
      abs(this[0] - (argb shr RED_SHIFT and BYTE)),
      abs(this[1] - (argb shr GREEN_SHIFT and BYTE)),
      abs(this[2] - (argb and BYTE)),
    )
}

private val CAMCORDER =
  Camcorder(listOf(OsdElement.Rec(), OsdElement.Counter(start = 900.milliseconds), OsdElement.Tape(TapeSpeed.SP)))

// Read at slot 6 the counter is 0.6 s in, frame 18 of its second.
private val COUNTING =
  Camcorder(listOf(OsdElement.Rec(), OsdElement.Counter(start = 400.milliseconds, withFrames = true)))
private const val FRAME_RATE = 30
private val OSD = CamcorderOsd(CAMCORDER, FRAME_RATE.toFloat())
private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private const val WIDTH = 320
private const val HEIGHT = 240
private const val SMALL_HEIGHT = 120
private const val FRAMES = 12
private val READ_TIME: Duration = outputSlotTime(6, FRAME_RATE)

// The counter starts at 0.9 s, so slot 2 reads 0:00:00 and slot 4 reads 0:00:01.
private val BEFORE_SECOND: Duration = outputSlotTime(2, FRAME_RATE)
private val AFTER_SECOND: Duration = outputSlotTime(4, FRAME_RATE)
private const val MID = 0.5f
private const val RGB = 3
private const val FULL = 255.0
private const val BYTE = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CLEAR = 0
private const val RAMP_FLOOR = 64
private const val RAMP_CODES = 128
private const val RAMP_MIDDLE = 128

// A texel the pass copies lands on its own eight bit code.
private const val EXACT_CODES = 1.0

// A read and the picture it is held against each round to one eight bit code, as the VHS reads find.
private const val SIGNAL_CODES = 1.0

// How far the tape has to move a display pixel to count as degraded.
private const val MOVED_CODES = 8.0
