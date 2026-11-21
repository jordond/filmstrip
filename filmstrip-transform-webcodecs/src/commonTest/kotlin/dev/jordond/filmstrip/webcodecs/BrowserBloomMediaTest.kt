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
import dev.jordond.filmstrip.effects.filters.bloom.Bloom
import dev.jordond.filmstrip.effects.filters.bloom.BloomParams
import dev.jordond.filmstrip.effects.filters.bloom.BloomSignal
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

/**
 * A [Bloom] drawn through the real browser compositor, read against [BloomSignal.bloomed] over [BloomSignal.glow].
 *
 * Reads come off a preview, which is the export's own lowering and pass with no encoder in the way. The picture a read
 * is held against is the same clip drawn with no [Bloom], so what the fixture's encoder and the decoder did to it is
 * in the expected value as well.
 */
class BrowserBloomMediaTest {
  @Test
  fun aCompositionBloomLandsOnTheSignalAtMidControls() =
    runTest {
      val bloom = Bloom(MID, MID, MID)
      val clip = edgeClip()

      val frame = drawn(edit(Clip(clip), listOf(bloom)))
      val picture = drawn(edit(Clip(clip)))

      val y = frame.size.height / 2
      val plan = BloomSignal.blurPlan(MID.toDouble(), frame.size.width)
      val past = frame.size.width / 2 + (plan.sigmaPx * plan.factor).toInt()
      withClue("one sigma past the bright edge glows") {
        (frame.pixelAt(past, y).red - picture.pixelAt(past, y).red).toDouble() shouldBeGreaterThan GLOW_CODES
      }
      worstCodes(frame, picture, bloom) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipBloomLandsOnTheSignalAtMidControls() =
    runTest {
      val bloom = Bloom(MID, MID, MID)
      val clip = edgeClip()

      val frame = drawn(edit(Clip(clip, effects = listOf(bloom))))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, bloom) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aLowThresholdAndATightSpreadLandOnTheSignal() =
    runTest {
      val bloom = Bloom(intensity = MID, threshold = LOW_THRESHOLD, spread = 0f)
      val clip = edgeClip()

      val frame = drawn(edit(Clip(clip), listOf(bloom)))
      val picture = drawn(edit(Clip(clip)))

      withClue("the grey side sits inside the knee, so it glows too") {
        val grey = GREY.channels()
        BloomSignal.response(grey[0], grey[1], grey[2], LOW_THRESHOLD.toDouble())[0] shouldBeGreaterThan 0.05
      }
      worstCodes(frame, picture, bloom) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aHighThresholdAndAWideSpreadLandOnTheSignal() =
    runTest {
      val bloom = Bloom(intensity = 1f, threshold = HIGH_THRESHOLD, spread = 1f)
      val clip = edgeClip()

      val frame = drawn(edit(Clip(clip), listOf(bloom)))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, bloom) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun dreamyLandsOnTheSignal() =
    runTest {
      val clip = edgeClip()

      val frame = drawn(edit(Clip(clip), listOf(Bloom.Dreamy)))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, Bloom.Dreamy) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aSmallerRenderDrawsTheSameBloomAtItsOwnSize() =
    runTest {
      val bloom = Bloom(MID, MID, MID)
      val clip = edgeClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(bloom)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe SMALL_HEIGHT
      worstCodes(frame, picture, bloom) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aLargeFrameBlursAtThePlansSmallerSize() =
    runTest {
      val bloom = Bloom(MID, MID, 1f)
      val clip = edgeClip(LARGE_WIDTH, LARGE_HEIGHT)

      val frame = drawn(edit(Clip(clip), listOf(bloom)))
      val picture = drawn(edit(Clip(clip)))

      val plan = BloomSignal.blurPlan(1.0, frame.size.width)
      withClue("the plan has to scale by a factor that leaves a remainder for this to tell") {
        (plan.factor > 2 && frame.size.width % plan.factor != 0) shouldBe true
      }
      worstCodes(frame, picture, bloom, step = LARGE_READ_STEP) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun theSameTimeDrawsTheSamePictureAfterASeek() =
    runTest {
      val preview = previewOf(edit(Clip(edgeClip()), listOf(Bloom())), SPEC)
      try {
        val first = preview.frameAt(READ_TIME).shouldNotBeNull()
        preview.frameAt(outputSlotTime(1, FRAME_RATE)).shouldNotBeNull()
        val again = preview.frameAt(READ_TIME).shouldNotBeNull()

        again.pixels.contentEquals(first.pixels) shouldBe true
      } finally {
        preview.release()
      }
    }

  @Test
  fun aCompositionBloomGlowsFromThePictureIntoABar() =
    runTest {
      val bloom = Bloom(1f, MID, MID)
      val wide = Crop(AspectRatio(21, 9), Fit.Contain)
      val bright = brightClip()
      val fill = Fill.Solid(BLACK_ARGB)

      val frame = drawn(edit(Clip(bright), listOf(wide, bloom), fill))
      val picture = drawn(edit(Clip(bright), listOf(wide), fill))

      val y = frame.size.height / 2
      withClue("the frame has to have bars on its sides for this to tell") { picture.pixelAt(0, y).red shouldBe 0 }
      val pictureLeft = (0 until frame.size.width).first { picture.pixelAt(it, y).red > BAR_BLACK_CODES }
      val plan = BloomSignal.blurPlan(MID.toDouble(), frame.size.width)
      val inBar = pictureLeft - (plan.sigmaPx * plan.factor).toInt()
      withClue("one sigma into the bar reads ${frame.pixelAt(inBar, y).red}") {
        frame.pixelAt(inBar, y).red.toDouble() shouldBeGreaterThan GLOW_CODES
      }
      worstCodes(frame, picture, bloom) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aClipBloomLeavesACanvasBarClearOverTheFill() =
    runTest {
      val bloom = Bloom(1f, MID, 1f)
      val square = MediaSource.Bytes(makeClip(SQUARE, SQUARE, FRAMES, FRAME_RATE) { _, _ -> BRIGHT })
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)

      val frame = drawn(edit(Clip(square, effects = listOf(wide, bloom)), fill = Fill.Solid(SLATE_ARGB)))

      val width = frame.size.width
      val height = frame.size.height
      val contentRight = (1.0 + height.toDouble() / width) / 2.0
      val justOutside = (contentRight * width).toInt() + 2
      withClue("a bar two pixels off a bright picture holds the fill, with no glow") {
        frame.pixelAt(justOutside, height / 2).isNear(SLATE, CLEAR_CODES) shouldBe true
      }
      withClue("the far end of the bar holds the fill") {
        frame.pixelAt(width - 1, height / 4).isNear(SLATE, CLEAR_CODES) shouldBe true
      }
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest {
      val swatch =
        ImageOverlay(
          ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), SWATCH_ARGB)),
          Corner.TopStart,
          scale = SWATCH_SCALE,
        )

      val frame = drawn(edit(Clip(brightClip()), listOf(swatch, Bloom(1f, MID, 1f))))

      frame.at(SWATCH_X, SWATCH_Y).isNear(Rgb(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE), CLEAR_CODES) shouldBe true
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
    fill: Fill = Fill.Solid(BLACK_ARGB),
  ) = Composition(
    tracks = listOf(Track(listOf(clip))),
    effects = effects,
    fill = fill,
    audio = AudioSpec.Remove,
  )

  // Bright on the left and grey on the right, with a warm tint so the glow carries a colour.
  private suspend fun edgeClip(
    width: Int = WIDTH,
    height: Int = HEIGHT,
  ): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(width, height, FRAMES, FRAME_RATE) { _, x, _ -> if (x < width / 2) BRIGHT else GREY },
    )

  private suspend fun brightClip(): MediaSource.Bytes =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> BRIGHT })

  // The largest gap between the frame and the signal over every pixel a step apart, in eight bit codes, with the glow
  // worked out from the picture with no Bloom on it.
  private fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    bloom: Bloom,
    step: Int = 1,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    val params = BloomParams.of(bloom)
    val glow = BloomSignal.glow(width, height, params) { x, y -> picture.pixelAt(x, y).channels() }
    var worst = 0.0
    for (y in 0 until height step step) {
      for (x in 0 until width step step) {
        val shown = picture.pixelAt(x, y).channels()
        val expected = BloomSignal.bloomed(shown[0], shown[1], shown[2], glow.at(x, y), params)
        val read = frame.pixelAt(x, y).channels()
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - expected[channel]) * FULL)
      }
    }
    return worst
  }

  private fun PreviewFrame.pixelAt(
    x: Int,
    y: Int,
  ): Rgb = at((x + HALF) / size.width, (y + HALF) / size.height)

  private fun Rgb.channels(): DoubleArray = doubleArrayOf(red / FULL, green / FULL, blue / FULL)
}

private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private val GREY = Rgb(96, 96, 96)
private val BRIGHT = Rgb(235, 215, 185)
private val SLATE = Rgb(0x20, 0x40, 0x60)

private const val MID = 0.5f
private const val LOW_THRESHOLD = 0.3f
private const val HIGH_THRESHOLD = 0.75f
private const val HALF = 0.5
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 320
private const val HEIGHT = 240
private const val SQUARE = 180
private const val LARGE_WIDTH = 1280
private const val LARGE_HEIGHT = 720
private const val LARGE_READ_STEP = 5
private const val SMALL_HEIGHT = 120
private const val FRAMES = 12
private const val FRAME_RATE = 30
private const val READ_SLOT = 6L
private val READ_TIME: Duration = outputSlotTime(READ_SLOT, FRAME_RATE)
private const val BLACK_ARGB = 0xFF000000.toInt()
private const val SLATE_ARGB = 0xFF204060.toInt()
private const val BAR_BLACK_CODES = 32
private const val SWATCH_CODE = 0x20
private const val SWATCH_ARGB = 0xFF202020.toInt()
private const val SWATCH_PX = 64
private const val SWATCH_SCALE = 0.3f
private const val SWATCH_X = 0.15
private const val SWATCH_Y = 0.2

// A read that stands clear of rounding one sigma past a bright edge.
private const val GLOW_CODES = 2.0

// A read and the picture it is held against each round to one eight bit code, and the blur targets each hold eight
// bits under the gain.
private const val SIGNAL_CODES = 1.5

// A fill through an eight bit canvas.
private const val CLEAR_CODES = 1
