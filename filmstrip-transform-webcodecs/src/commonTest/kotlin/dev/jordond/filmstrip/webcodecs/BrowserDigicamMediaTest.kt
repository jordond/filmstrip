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
import dev.jordond.filmstrip.effects.filters.digicam.Digicam
import dev.jordond.filmstrip.effects.filters.digicam.DigicamParams
import dev.jordond.filmstrip.effects.filters.digicam.DigicamSignal
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
 * A [Digicam] drawn through the real browser compositor, read against [DigicamSignal.pixelAt].
 *
 * Reads come off a preview, which is the export's own lowering and pass with no encoder in the way. The picture a read
 * is held against is the same clip drawn with no [Digicam], so what the fixture's encoder and the decoder did to it is
 * in the expected value as well.
 */
class BrowserDigicamMediaTest {
  @Test
  fun aCompositionDigicamLandsOnTheSignalAtMidControls() =
    runTest {
      val digicam = Digicam(MID, MID, MID)
      val clip = rampClip()

      val frame = drawn(edit(Clip(clip), listOf(digicam)))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, digicam) shouldBeLessThan signalCodes(digicam)
    }

  @Test
  fun aClipDigicamLandsOnTheSignalAtMidControls() =
    runTest {
      val digicam = Digicam(MID, MID, MID)
      val clip = rampClip()

      val frame = drawn(edit(Clip(clip, effects = listOf(digicam))))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, digicam) shouldBeLessThan signalCodes(digicam)
    }

  @Test
  fun theHaloOvershootsBothSidesOfAnEdge() =
    runTest {
      val digicam = Digicam(sharpness = MID, noise = 0f, clip = 0f)
      val clip = rampClip(LARGE_WIDTH, LARGE_HEIGHT)

      val frame = drawn(edit(Clip(clip), listOf(digicam)))
      val picture = drawn(edit(Clip(clip)))

      val edge = frame.size.width / 2
      val top = frame.size.height / 8
      val bottom = frame.size.height * 7 / 8
      withClue("the blur has to reach the next pixel for this to tell") {
        DigicamSignal.blurPlan(frame.size.width).weights[1] shouldBeGreaterThan REACHING_WEIGHT
      }
      withClue("the ramp is darker than the grey near the top, so its last column dips and the grey lifts") {
        (picture.pixelAt(edge - 1, top).red - frame.pixelAt(edge - 1, top).red).toDouble() shouldBeGreaterThan
          HALO_CODES
        (frame.pixelAt(edge, top).red - picture.pixelAt(edge, top).red).toDouble() shouldBeGreaterThan HALO_CODES
      }
      withClue("the ramp is brighter than the grey near the bottom, so its last column lifts and the grey dips") {
        (frame.pixelAt(edge - 1, bottom).red - picture.pixelAt(edge - 1, bottom).red)
          .toDouble() shouldBeGreaterThan HALO_CODES
        (picture.pixelAt(edge, bottom).red - frame.pixelAt(edge, bottom).red).toDouble() shouldBeGreaterThan HALO_CODES
      }
      worstCodes(frame, picture, digicam, columns = edge - EDGE_COLUMNS until edge + EDGE_COLUMNS) shouldBeLessThan
        signalCodes(digicam)
    }

  @Test
  fun aBrightPatchClipsAtTheMiddleOfTheRange() =
    runTest {
      val digicam = Digicam(sharpness = 0f, noise = 0f, clip = MID)
      val params = DigicamParams.of(digicam)
      val clip = rampClip()

      val frame = drawn(edit(Clip(clip), listOf(digicam)))
      val picture = drawn(edit(Clip(clip)))

      val x = frame.size.width / 4
      val bright = picture.pixelAt(x, frame.size.height - 2)
      val mid = picture.pixelAt(x, frame.size.height / 2)
      withClue("the bottom of the ramp has to clip and its middle must not for this to tell") {
        (bright.red / FULL * params.clipGain) shouldBeGreaterThan 1.0
        (mid.red / FULL * params.clipGain) shouldBeLessThan 0.9
      }
      frame.pixelAt(x, frame.size.height - 2).red shouldBe FULL.toInt()
      worstCodes(frame, picture, digicam) shouldBeLessThan signalCodes(digicam)
    }

  @Test
  fun noiseSpreadsChromaInADarkPatchAndAveragesOut() =
    runTest {
      val digicam = Digicam(sharpness = 0f, noise = MID, clip = 0f)
      val clip = MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> DARK })

      val frame = drawn(edit(Clip(clip), listOf(digicam)))
      val picture = drawn(edit(Clip(clip)))

      val moves =
        (0 until frame.size.height).flatMap { y ->
          (0 until frame.size.width).map { x -> frame.pixelAt(x, y).blue - picture.pixelAt(x, y).blue }
        }
      val dark = DARK.channels()
      val pushed = DigicamSignal.noised(dark[0], dark[1], dark[2], doubleArrayOf(1.0, 0.0), DigicamParams.of(digicam))
      val reach = (pushed[2] - dark[2]) * FULL
      withClue("the noise reaches most of the way to its amplitude on both sides") {
        moves.max().toDouble() shouldBeGreaterThan reach * NEAR_REACH
        moves.min().toDouble() shouldBeLessThan -reach * NEAR_REACH
      }
      abs(moves.average()) shouldBeLessThan MEAN_CODES
      worstCodes(frame, picture, digicam) shouldBeLessThan signalCodes(digicam)
    }

  @Test
  fun ccdLandsOnTheSignal() =
    runTest {
      val clip = rampClip()

      val frame = drawn(edit(Clip(clip), listOf(Digicam.Ccd)))
      val picture = drawn(edit(Clip(clip)))

      worstCodes(frame, picture, Digicam.Ccd) shouldBeLessThan signalCodes(Digicam.Ccd)
    }

  @Test
  fun aSmallerRenderDrawsTheSameLookAtItsOwnSize() =
    runTest {
      val digicam = Digicam(MID, MID, MID)
      val clip = rampClip()
      val small = ExportSpec(heightPx = SMALL_HEIGHT, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(digicam)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe SMALL_HEIGHT
      worstCodes(frame, picture, digicam) shouldBeLessThan scaledCodes(digicam)
    }

  @Test
  fun aLargeFrameDrawsCellsSeveralPixelsWide() =
    runTest {
      val digicam = Digicam(MID, MID, MID)
      val clip = rampClip(LARGE_WIDTH, LARGE_HEIGHT)

      val frame = drawn(edit(Clip(clip), listOf(digicam)))
      val picture = drawn(edit(Clip(clip)))

      withClue("a cell has to span more than a pixel for this to tell") {
        (DigicamSignal.cellPx(frame.size.height) > 1) shouldBe true
      }
      worstCodes(frame, picture, digicam, step = LARGE_READ_STEP) shouldBeLessThan signalCodes(digicam)
    }

  @Test
  fun theSameTimeDrawsTheSamePictureAfterASeekAndAnotherTickDrawsOtherNoise() =
    runTest {
      val preview = previewOf(edit(Clip(rampClip()), listOf(Digicam())), SPEC)
      try {
        val first = preview.frameAt(READ_TIME).shouldNotBeNull()
        val other = preview.frameAt(outputSlotTime(1, FRAME_RATE)).shouldNotBeNull()
        val again = preview.frameAt(READ_TIME).shouldNotBeNull()

        again.pixels.contentEquals(first.pixels) shouldBe true
        other.pixels.contentEquals(first.pixels) shouldBe false
      } finally {
        preview.release()
      }
    }

  @Test
  fun aClipDigicamLeavesACanvasBarClearOverTheFill() =
    runTest {
      val square = MediaSource.Bytes(makeClip(SQUARE, SQUARE, FRAMES, FRAME_RATE) { _, _ -> DARK })
      val wide = Crop(AspectRatio(16, 9), Fit.Contain)

      val frame = drawn(edit(Clip(square, effects = listOf(wide, Digicam.Ccd)), fill = Fill.Solid(SLATE_ARGB)))

      val width = frame.size.width
      val height = frame.size.height
      val contentRight = (1.0 + height.toDouble() / width) / 2.0
      val justOutside = (contentRight * width).toInt() + 2
      withClue("a bar two pixels off the picture holds the fill, with no halo and no noise") {
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

      val frame = drawn(edit(Clip(rampClip()), listOf(swatch, Digicam.Ccd)))

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

  // A warm ramp from dark at the top to bright at the bottom on the left, against flat grey on the right, so the edge
  // between them is dark against light at one end and light against dark at the other.
  private suspend fun rampClip(
    width: Int = WIDTH,
    height: Int = HEIGHT,
  ): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(width, height, FRAMES, FRAME_RATE) { _, x, y ->
        if (x >= width / 2) {
          GREY
        } else {
          val lift = y * RAMP_SPAN / (height - 1)
          Rgb(RAMP_RED + lift, RAMP_GREEN + lift, RAMP_BLUE + lift)
        }
      },
    )

  // The largest gap between the frame and the signal over every pixel a step apart, in eight bit codes, with the blur
  // worked out from the picture with no Digicam on it.
  private fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    digicam: Digicam,
    step: Int = 1,
    columns: IntProgression? = null,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    val params = DigicamParams.of(digicam)
    val tick = DigicamSignal.tickAt(READ_TIME.inWholeMicroseconds / MICROS)
    val blur = DigicamSignal.blur(width, height) { x, y -> picture.pixelAt(x, y).channels() }
    var worst = 0.0
    for (y in 0 until height step step) {
      for (x in columns ?: (0 until width step step)) {
        val shown = picture.pixelAt(x, y).channels()
        val expected = DigicamSignal.pixelAt(x, y, height, digicam.seed, tick, params, shown, blur.at(x, y))
        val read = frame.pixelAt(x, y).channels()
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - expected[channel]) * FULL)
      }
    }
    return worst
  }

  // A read rounds to one eight bit code, and the blur's target holds eight bits, which the halo and the clip gain
  // scale up.
  private fun signalCodes(digicam: Digicam): Double {
    val params = DigicamParams.of(digicam)
    return ROUNDING_CODES + ROUNDING_CODES * params.haloGain * params.clipGain + SLACK_CODES
  }

  // A frame scaled on the way in reaches the look finer than the eight bits the picture reads back at, and the halo
  // and the clip gain scale that rounding up too.
  private fun scaledCodes(digicam: Digicam): Double {
    val params = DigicamParams.of(digicam)
    return signalCodes(digicam) + ROUNDING_CODES * (1.0 + params.haloGain) * params.clipGain
  }

  private fun PreviewFrame.pixelAt(
    x: Int,
    y: Int,
  ): Rgb = at((x + HALF) / size.width, (y + HALF) / size.height)

  private fun Rgb.channels(): DoubleArray = doubleArrayOf(red / FULL, green / FULL, blue / FULL)
}

private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private val GREY = Rgb(120, 120, 120)
private val DARK = Rgb(48, 48, 48)
private val SLATE = Rgb(0x20, 0x40, 0x60)

private const val MID = 0.5f
private const val HALF = 0.5
private const val FULL = 255.0
private const val MICROS = 1_000_000.0
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
private const val SWATCH_CODE = 0x20
private const val SWATCH_ARGB = 0xFF202020.toInt()
private const val SWATCH_PX = 64
private const val SWATCH_SCALE = 0.3f
private const val SWATCH_X = 0.15
private const val SWATCH_Y = 0.2

// The ramp's top colour and how far it climbs, which ends under white with a warm tint.
private const val RAMP_RED = 40
private const val RAMP_GREEN = 30
private const val RAMP_BLUE = 20
private const val RAMP_SPAN = 200

// A halo that stands clear of rounding beside an edge, the columns read either side of it, and a blur weight that
// moves a code.
private const val HALO_CODES = 2.0
private const val EDGE_COLUMNS = 4
private const val REACHING_WEIGHT = 0.1

// How much of the noise's reach a patch of this size has to show, and how far its mean may sit from nothing.
private const val NEAR_REACH = 0.8
private const val MEAN_CODES = 1.0

private const val ROUNDING_CODES = 0.5
private const val SLACK_CODES = 0.25

// A fill through an eight bit canvas.
private const val CLEAR_CODES = 1
