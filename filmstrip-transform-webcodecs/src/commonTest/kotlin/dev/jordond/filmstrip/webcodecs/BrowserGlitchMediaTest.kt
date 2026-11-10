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
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.glitch.GlitchParams
import dev.jordond.filmstrip.effects.filters.glitch.GlitchSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A [Glitch] drawn through the real browser compositor, read against [GlitchSignal].
 *
 * Reads come off a preview, which is the export's own lowering and pass with no encoder in the way. The picture a read
 * is held against is the same clip drawn with no [Glitch], so what the fixture's encoder and the decoder did to it is
 * in the expected value as well. The seed is the first whose burst is rising at the read, found through the signal.
 */
class BrowserGlitchMediaTest {
  @Test
  fun aCompositionGlitchLandsOnTheSignalMidAttack() =
    runTest {
      val clip = teethClip()

      val frame = drawn(edit(Clip(clip), listOf(BURSTING)))
      val picture = drawn(edit(Clip(clip)))

      withClue("some row the read covers slides at least a pixel") {
        val params = GlitchParams.of(BURSTING)
        (0 until HEIGHT step ROW_STEP).any { y ->
          val row = GlitchSignal.virtualRow(y, HEIGHT)
          abs(GlitchSignal.rowShift(BURSTING.seed, TICK, row, params)) * WIDTH > 1.0
        } shouldBe true
      }
      assertLands(frame, picture, BURSTING)
    }

  @Test
  fun aClipGlitchLandsOnTheSignalMidAttack() =
    runTest {
      val clip = teethClip()

      val frame = drawn(edit(Clip(clip, effects = listOf(BURSTING))))
      val picture = drawn(edit(Clip(clip)))

      assertLands(frame, picture, BURSTING)
    }

  @Test
  fun aQuietTickSplitsTheColoursAndMovesNoRow() =
    runTest {
      val clip = teethClip()

      val frame = drawn(edit(Clip(clip), listOf(QUIET)))
      val picture = drawn(edit(Clip(clip)))

      assertLands(frame, picture, QUIET)
    }

  @Test
  fun aSmallerRenderDrawsTheSameBurstAtItsOwnVirtualRows() =
    runTest {
      val clip = teethClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(BURSTING)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe SMALL_HEIGHT
      assertLands(frame, picture, BURSTING)
    }

  @Test
  fun theSameTimeDrawsTheSamePictureAfterASeek() =
    runTest {
      val preview = previewOf(edit(Clip(teethClip()), listOf(BURSTING)), SPEC)
      try {
        val first = preview.frameAt(READ_TIME).shouldNotBeNull()
        val elsewhere = preview.frameAt(outputSlotTime(1, FRAME_RATE)).shouldNotBeNull()
        val again = preview.frameAt(READ_TIME).shouldNotBeNull()

        withClue(
          "the burst moves between the two times",
        ) { elsewhere.pixels.contentEquals(first.pixels) shouldBe false }
        withClue("the same time draws the same picture") { again.pixels.contentEquals(first.pixels) shouldBe true }
      } finally {
        preview.release()
      }
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest {
      val frame = drawn(edit(Clip(greyClip()), listOf(swatch(), BURSTING)))

      abs(frame.at(SWATCH_X, SWATCH_Y).mean() - SWATCH_CODE) shouldBeLessThan FLAT_CODES
    }

  @Test
  fun aClearBarBesideThePictureHoldsTheFillWhereTheSplitReadsPicture() =
    runTest {
      val square = MediaSource.Bytes(makeClip(HEIGHT, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> GREY })
      val wide = Crop(AspectRatio(WIDTH, HEIGHT), Fit.Contain)
      val params = GlitchParams.of(BURSTING)

      val frame = drawn(edit(Clip(square, effects = listOf(wide, BURSTING))))
      val plain = drawn(edit(Clip(square, effects = listOf(wide))))
      val split = GlitchSignal.channelSplit(BURSTING.seed, TICK, params) * WIDTH
      val y =
        (0 until HEIGHT).first {
          GlitchSignal.rowShift(BURSTING.seed, TICK, GlitchSignal.virtualRow(it, HEIGHT), params) == 0.0
        }
      val left = (WIDTH - HEIGHT) / 2
      val gap = { column: Int, channel: Int ->
        val at = (column + 0.5) / WIDTH to (y + 0.5) / HEIGHT
        abs(frame.at(at.first, at.second).channels()[channel] - plain.at(at.first, at.second).channels()[channel]) *
          FULL
      }

      frame.size.width shouldBe WIDTH
      withClue("the split reaches across the edge") { split shouldBeGreaterThan HALF_PIXEL }
      withClue("red beside the right edge") { gap(left + HEIGHT, 0) shouldBeLessThan SIGNAL_CODES }
      withClue("blue beside the left edge") { gap(left - 1, 2) shouldBeLessThan SIGNAL_CODES }
    }

  private suspend fun drawn(
    composition: Composition,
    spec: ExportSpec = SPEC,
  ): PreviewFrame {
    val preview = previewOf(composition, spec)
    try {
      return preview.frameAt(READ_TIME).shouldNotBeNull()
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
    clip: Clip,
    effects: List<EffectSpec> = emptyList(),
  ) = Composition(
    tracks = listOf(Track(listOf(clip))),
    effects = effects,
    fill = Fill.Solid(BLACK_ARGB),
    audio = AudioSpec.Remove,
  )

  // Grey teeth rising and falling across the width, steep enough that a split or a slide reads codes off.
  private suspend fun teethClip(): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, x, _ ->
        val phase = x % TOOTH_PX
        val code = TOOTH_LOW + minOf(phase, TOOTH_PX - phase) * TOOTH_SLOPE
        Rgb(code, code, code)
      },
    )

  private suspend fun greyClip(): MediaSource.Bytes =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> GREY })

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), SWATCH_ARGB)),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  // Holds the frame to the signal across rows top to bottom and columns edge to edge, in eight bit codes, with the
  // picture bilinear over its columns and held at its edges the way the sampler reads it. Red and blue swapped, or a
  // burst's rows left still, would have to read well past the tolerance somewhere, so the reads can tell.
  private fun assertLands(
    frame: PreviewFrame,
    picture: PreviewFrame,
    glitch: Glitch,
  ) {
    val width = frame.size.width
    val height = frame.size.height
    val params = GlitchParams.of(glitch)
    val split = GlitchSignal.channelSplit(glitch.seed, TICK, params)
    var worst = 0.0
    var swapGap = 0.0
    var stillGap = 0.0
    (0 until height step ROW_STEP).forEach { y ->
      val row = GlitchSignal.virtualRow(y, height)
      val shift = GlitchSignal.rowShift(glitch.seed, TICK, row, params)
      val texel = { column: Int -> picture.at((column + 0.5) / width, (y + 0.5) / height).channels() }
      val nearest = { at: Double -> texel((at.coerceIn(0.0, 1.0) * width).toInt().coerceAtMost(width - 1)) }
      ((0 until width step COLUMN_STEP) + (width - 1)).forEach { column ->
        val x = (column + 0.5) / width
        val expected = GlitchSignal.pixelAt(x, row, TICK, glitch.seed, width, params, texel)
        val read = frame.at(x, (y + 0.5) / height).channels()
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - expected[channel]) * FULL)
        swapGap = max(swapGap, abs(nearest(x - shift + split)[0] - expected[0]) * FULL)
        stillGap = max(stillGap, abs(nearest(x)[1] - expected[1]) * FULL)
      }
    }
    withClue("red and blue swapped would read $swapGap codes off") { swapGap shouldBeGreaterThan 2 * SIGNAL_CODES }
    if (glitch == BURSTING) {
      withClue("a row left still would read $stillGap codes off") { stillGap shouldBeGreaterThan 2 * SIGNAL_CODES }
    }
    withClue("worst $worst") { worst shouldBeLessThan SIGNAL_CODES }
  }

  private fun Rgb.channels(): DoubleArray = doubleArrayOf(red / FULL, green / FULL, blue / FULL)

  private fun Rgb.mean(): Double = (red + green + blue) / RGB.toDouble()
}

private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private val GREY = Rgb(96, 96, 96)

private const val MID = 0.5f
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 320
private const val HEIGHT = 240
private const val SMALL_HEIGHT = 120
private const val ROW_STEP = 7
private const val COLUMN_STEP = 3
private const val HALF_PIXEL = 0.5
private const val FRAMES = 12
private const val FRAME_RATE = 30
private const val READ_SLOT = 6L
private val READ_TIME: Duration = outputSlotTime(READ_SLOT, FRAME_RATE)
private val TICK: Int = GlitchSignal.tickAt(READ_TIME.toDouble(DurationUnit.SECONDS))
private val MID_PARAMS = GlitchParams.at(MID.toDouble())

// The first seed whose burst at the read is still rising, and the first with no burst there.
private val BURSTING: Glitch =
  Glitch(
    MID,
    seed =
      (1..SEED_SEARCH).first { seed ->
        val now = GlitchSignal.burstAt(seed, TICK, MID_PARAMS)?.strength ?: 0.0
        val next = GlitchSignal.burstAt(seed, TICK + 1, MID_PARAMS)?.strength ?: 0.0
        now > 0.0 && next > now
      },
  )
private val QUIET: Glitch =
  Glitch(
    MID,
    seed =
      (1..SEED_SEARCH).first {
        GlitchSignal.burstAt(it, TICK, MID_PARAMS) ==
          null
      },
  )
private const val SEED_SEARCH = 100_000

private const val TOOTH_PX = 32
private const val TOOTH_LOW = 32
private const val TOOTH_SLOPE = 12
private const val BLACK_ARGB = 0xFF000000.toInt()
private const val SWATCH_CODE = 0x20
private const val SWATCH_ARGB = 0xFF202020.toInt()
private const val SWATCH_PX = 64
private const val SWATCH_SCALE = 0.3f
private const val SWATCH_X = 0.15
private const val SWATCH_Y = 0.2

// A read and the picture it is held against each round to one eight bit code.
private const val SIGNAL_CODES = 1.0

// The mean of three rounded channels on a flat area.
private const val FLAT_CODES = 1.5
