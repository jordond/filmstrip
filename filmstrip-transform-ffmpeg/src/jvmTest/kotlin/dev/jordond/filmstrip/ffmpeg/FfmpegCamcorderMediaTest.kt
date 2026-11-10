package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderOsd
import dev.jordond.filmstrip.effects.filters.camcorder.OsdCorner
import dev.jordond.filmstrip.effects.filters.camcorder.OsdElement
import dev.jordond.filmstrip.effects.filters.camcorder.OsdRaster
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.BYTE
import dev.jordond.filmstrip.ffmpeg.support.CAMCORDER_CLEAR
import dev.jordond.filmstrip.ffmpeg.support.CAMCORDER_LUMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.OPAQUE_BLACK
import dev.jordond.filmstrip.ffmpeg.support.RGBA
import dev.jordond.filmstrip.ffmpeg.support.assertOnCamcorderRaster
import dev.jordond.filmstrip.ffmpeg.support.chromaCodesOf
import dev.jordond.filmstrip.ffmpeg.support.lumaCodeOf
import dev.jordond.filmstrip.ffmpeg.support.samplesWhollyOf
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [Camcorder] drawn by ffmpeg, its luma read off the graph's own planes against [CamcorderOsd.rasterAt].
 *
 * The overlay lands on 4:2:0 codes, so luma is read pixel for pixel and chroma, which the scaler filters over the
 * pixels round each sample, only where a sample and its neighbours sit wholly inside one colour.
 */
class FfmpegCamcorderMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `every lit and edge pixel lands on the raster's luma`() =
    runBlocking<Unit> {
      val clip = rampClip()

      val frame = draw.planesAt(edit(clip, listOf(CAMCORDER)), READ_SLOT)
      val picture = draw.planesAt(edit(clip), READ_SLOT)

      assertOnCamcorderRaster(frame, picture, OSD.rasterAt(slotTime(READ_SLOT), WIDTH, HEIGHT))
    }

  @Test
  fun `a frame each side of a second shows its own display`() =
    runBlocking<Unit> {
      val clip = rampClip()
      val before = OSD.rasterAt(slotTime(BEFORE_SLOT), WIDTH, HEIGHT)
      val after = OSD.rasterAt(slotTime(AFTER_SLOT), WIDTH, HEIGHT)

      withClue("the counter has to change across the second for this to tell") {
        differing(before, after) shouldBeGreaterThan
          0
      }
      assertOnCamcorderRaster(
        draw.planesAt(edit(clip, listOf(CAMCORDER)), BEFORE_SLOT),
        draw.planesAt(edit(clip), BEFORE_SLOT),
        before,
      )
      assertOnCamcorderRaster(
        draw.planesAt(edit(clip, listOf(CAMCORDER)), AFTER_SLOT),
        draw.planesAt(edit(clip), AFTER_SLOT),
        after,
      )
    }

  @Test
  fun `a frame count mid second at 29_97 lands on the raster's luma`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, RAMP, seconds = CLIP_SECONDS, rate = "30000/1001")
      val osd = CamcorderOsd(CAMCORDER, NTSC)

      assertOnCamcorderRaster(
        draw.planesAt(edit(clip, listOf(CAMCORDER)), NTSC_SLOT),
        draw.planesAt(edit(clip), NTSC_SLOT),
        osd.rasterAt(NTSC_TIME, WIDTH, HEIGHT),
      )
    }

  @Test
  fun `a windowed preview seeked past a second boundary draws the frame the export does`() =
    runBlocking<Unit> {
      val composition = edit(rampClip(), listOf(CAMCORDER))
      val seek = slotTime(SEEK_SLOT)

      val windowed = draw.frames(composition, at = seek, count = 1).single()
      val fromStart = draw.frames(composition, at = Duration.ZERO, count = SEEK_SLOT + 1).last()

      withClue("the display has to differ from the first frame's for this to tell") {
        differing(OSD.rasterAt(seek, WIDTH, HEIGHT), OSD.rasterAt(Duration.ZERO, WIDTH, HEIGHT)) shouldBeGreaterThan 0
      }
      val shown = OSD.rasterAt(seek, WIDTH, HEIGHT)
      val first = OSD.rasterAt(Duration.ZERO, WIDTH, HEIGHT)
      var read = 0
      for (y in 0 until HEIGHT) {
        for (x in 0 until WIDTH) {
          val argb = shown.argbAt(x, y)
          if (argb == CLEAR || abs(lumaCodeOf(argb) - lumaCodeOf(first.argbAt(x, y))) < SWING_CODES) continue
          read++
          withClue("windowed ($x, $y)") {
            abs(rgbaLuma(windowed, x, y) - lumaCodeOf(argb)) shouldBeLessThanOrEqual
              RGBA_CODES
          }
          withClue("from the start ($x, $y)") {
            abs(rgbaLuma(fromStart, x, y) - lumaCodeOf(argb)) shouldBeLessThanOrEqual
              RGBA_CODES
          }
        }
      }
      read shouldBeGreaterThan 0
    }

  @Test
  fun `a display ahead of vhs is recorded on the tape and one after it stays clean`() =
    runBlocking<Unit> {
      val clip = rampClip()
      val vhs = Vhs(intensity = MID)
      val raster = OSD.rasterAt(slotTime(READ_SLOT), WIDTH, HEIGHT)

      val burned = draw.planesAt(edit(clip, listOf(CAMCORDER, vhs)), READ_SLOT)
      val clean = draw.planesAt(edit(clip, listOf(vhs, CAMCORDER)), READ_SLOT)

      var moved = 0
      forEachLit(raster) { x, y, argb ->
        if (abs(burned.luma(x, y) - lumaCodeOf(argb)) > MOVED_CODES) moved++
        withClue("clean ($x, $y)") { abs(clean.luma(x, y) - lumaCodeOf(argb)) shouldBeLessThanOrEqual LUMA_CODES }
      }
      withClue("the tape moved the display off its own codes") { moved shouldBeGreaterThan 0 }
    }

  @Test
  fun `a smaller export draws the raster at its own size`() =
    runBlocking<Unit> {
      val clip = rampClip()
      val spec = ExportSpec(heightPx = SMALL_HEIGHT)

      val frame = draw.planesAt(edit(clip, listOf(CAMCORDER)), READ_SLOT, spec)
      val picture = draw.planesAt(edit(clip), READ_SLOT, spec)

      frame.width shouldBe SMALL_WIDTH
      assertOnCamcorderRaster(frame, picture, OSD.rasterAt(slotTime(READ_SLOT), SMALL_WIDTH, SMALL_HEIGHT))
    }

  @Test
  fun `a sample inside the red dot or inside white text holds that colour's chroma`() =
    runBlocking<Unit> {
      val raster = CamcorderOsd(BOLD, FILTER_RATE.toFloat()).rasterAt(slotTime(READ_SLOT), LARGE_WIDTH, LARGE_HEIGHT)
      val frame =
        draw.planesAt(
          edit(draw.clipOf(LARGE_WIDTH, LARGE_HEIGHT, RAMP, seconds = 1), listOf(BOLD)),
          READ_SLOT,
        )

      val lit =
        (0 until LARGE_HEIGHT)
          .flatMap { y -> (0 until LARGE_WIDTH).map { x -> raster.argbAt(x, y) } }
          .filter { it != CLEAR && it != OPAQUE_BLACK }
          .distinct()

      withClue("the raster draws the red dot and white text") { lit.size shouldBe 2 }
      lit.forEach { colour ->
        val (cb, cr) = chromaCodesOf(colour).let { it[0] to it[1] }
        val samples = samplesWhollyOf(raster, colour)
        withClue("a sample wholly inside ${colour.toUInt().toString(16)}") { samples.shouldNotBeEmpty() }
        samples.forEach { (column, row) ->
          withClue("Cb at ($column, $row)") { abs(frame.cb(column, row) - cb) shouldBeLessThanOrEqual CHROMA_CODES }
          withClue("Cr at ($column, $row)") { abs(frame.cr(column, row) - cr) shouldBeLessThanOrEqual CHROMA_CODES }
        }
      }
    }

  @Test
  fun `a span with no end is refused by name`() {
    val frame = Size(WIDTH, HEIGHT)
    val open =
      Attributes(
        inputSize = frame,
        outputSize = frame,
        layoutSize = frame,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = FILTER_RATE.toFloat(),
        span = TimeRange(Duration.ZERO),
      )

    val refused =
      FilterEffectResolver()
        .resolve(CAMCORDER, FILTER_GRAPH, open)
        .shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.specId shouldBe Camcorder.ID
    refused.message shouldContain "no end"
  }

  private fun forEachLit(
    raster: OsdRaster,
    each: (Int, Int, Int) -> Unit,
  ) {
    for (y in 0 until raster.height) {
      for (x in 0 until raster.width) raster.argbAt(x, y).takeIf { it != CLEAR }?.let { each(x, y, it) }
    }
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

  // The limited range luma code of a pixel in an RGBA frame the preview hands back.
  private fun rgbaLuma(
    frame: ByteArray,
    x: Int,
    y: Int,
  ): Int {
    val at = (y * WIDTH + x) * RGBA
    val argb =
      (frame[at].toInt() and BYTE shl 16) or (frame[at + 1].toInt() and BYTE shl 8) or (frame[at + 2].toInt() and BYTE)
    return lumaCodeOf(argb)
  }

  private fun rampClip() = draw.clipOf(WIDTH, HEIGHT, RAMP, seconds = CLIP_SECONDS)

  private fun edit(
    clip: dev.jordond.filmstrip.edit.Clip,
    effects: List<EffectSpec> = emptyList(),
  ): Composition = draw.composition(listOf(clip), effects)

  private fun slotTime(slot: Int): Duration = outputSlotTime(slot.toLong(), FILTER_RATE)

  private companion object {
    val CAMCORDER =
      Camcorder(
        listOf(
          OsdElement.Rec(),
          OsdElement.Counter(start = 900.milliseconds, withFrames = true),
          OsdElement.Tape(TapeSpeed.SP),
          OsdElement.Clock(),
        ),
      )
    val OSD = CamcorderOsd(CAMCORDER, FILTER_RATE.toFloat())
    val FILTER_GRAPH =
      RenderCapabilities(
        api = RenderApi.FilterGraph,
        supportsFragmentShader = false,
        supportsComputeShader = false,
        supportsHdr = true,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 16_384,
        features = emptySet(),
      )
    const val WIDTH = 320
    const val HEIGHT = 240
    const val CLIP_SECONDS = 3
    const val READ_SLOT = 6
    val RAMP = { x: Double -> doubleArrayOf(0.25 + x / 2, 0.5, 0.75 - x / 2) }
    const val NTSC = 30_000f / 1_001f

    // Slot 75 at 29.97 starts 2.5025 s in, frame 12 of the counter's fourth second, which starts at 0.9 s.
    const val NTSC_SLOT = 75
    val NTSC_TIME = (NTSC_SLOT * 1_001_000_000L / 30_000).microseconds

    // The counter starts at 0.9 s, so slot 2 reads 0:00:00 and slot 4 reads 0:00:01.
    const val BEFORE_SLOT = 2
    const val AFTER_SLOT = 4
    const val SEEK_SLOT = 47
    const val MID = 0.5f
    const val CLEAR = CAMCORDER_CLEAR
    const val LUMA_CODES = CAMCORDER_LUMA_CODES
    const val SMALL_WIDTH = 160
    const val SMALL_HEIGHT = 120

    // Large enough that a stroke of text is wider than the scaler reads round a chroma sample.
    const val LARGE_WIDTH = 960
    const val LARGE_HEIGHT = 720
    val BOLD =
      Camcorder(listOf(OsdElement.Rec(), OsdElement.Label("SP", OsdCorner.TopRight)), textSize = 0.2f)

    // A sample wholly inside one colour holds that colour's own chroma, give or take swscale's rounding.
    const val CHROMA_CODES = 1

    // How far apart the two displays' luma has to be for a pixel to tell them apart.
    const val SWING_CODES = 64

    // Luma read back off RGBA, which has been through 4:2:0 and back. Chroma at a coloured edge moves it a little.
    const val RGBA_CODES = 8

    // How far the tape has to move a display pixel to count as degraded.
    const val MOVED_CODES = 8
  }
}
