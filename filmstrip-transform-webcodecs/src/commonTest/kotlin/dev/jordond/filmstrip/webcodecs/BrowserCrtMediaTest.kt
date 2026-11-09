package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.CrtParams
import dev.jordond.filmstrip.effects.filters.crt.CrtSignal
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration

/**
 * A [Crt] drawn through the real browser compositor, read against [CrtSignal] and against itself at a larger size.
 */
class BrowserCrtMediaTest : BrowserCrtFixture() {
  @Test
  fun aCompositionCrtLandsOnTheSignalAtAMidIntensity() =
    runTest {
      assertLandsOnTheSignal(WIDTH, HEIGHT) { clip, crt -> edit(Clip(clip), listOf(crt)) }
    }

  @Test
  fun aClipCrtLandsOnTheSignalAtAMidIntensity() =
    runTest {
      assertLandsOnTheSignal(WIDTH, HEIGHT) { clip, crt -> edit(Clip(clip, effects = listOf(crt))) }
    }

  @Test
  fun a1080pFrameLandsOnTheSignal() =
    runTest {
      assertLandsOnTheSignal(FULL_HD_WIDTH, FULL_HD_HEIGHT) { clip, crt -> edit(Clip(clip), listOf(crt)) }
    }

  @Test
  fun aFrameOddToThePatternsLandsOnTheSignal() =
    runTest {
      assertLandsOnTheSignal(UNEVEN_WIDTH, UNEVEN_HEIGHT) { clip, crt -> edit(Clip(clip), listOf(crt)) }
    }

  @Test
  fun aTallFrameLandsOnTheSignal() =
    runTest {
      assertLandsOnTheSignal(HEIGHT, WIDTH) { clip, crt -> edit(Clip(clip), listOf(crt)) }
    }

  @Test
  fun thePictureEndsInBlackOnEachSideOfTheCurve() =
    runTest {
      val crt = Crt(FULL_INTENSITY)
      val clip = flatClip(WIDTH, HEIGHT, BRIGHT)

      val frame = drawn(edit(Clip(clip), listOf(crt)))
      val picture = drawn(edit(Clip(clip)))

      val k = CrtParams.of(crt).barrelK
      val column = (0 until WIDTH).first { litAt(it, HEIGHT / 2, k) }
      val row = (0 until HEIGHT).first { litAt(WIDTH / 2, it, k) }
      withClue("the curve has to leave a margin for this to tell") {
        (column > 1 && row > 1) shouldBe true
      }

      frame.pixelAt(column - 1, HEIGHT / 2).isNear(Rgb.Black, CLEAR_CODES) shouldBe true
      frame.pixelAt(column + 2, HEIGHT / 2).red.toDouble() shouldBeGreaterThan LIT_CODES
      frame.pixelAt(WIDTH / 2, row - 1).isNear(Rgb.Black, CLEAR_CODES) shouldBe true
      frame.pixelAt(WIDTH / 2, row + 2).red.toDouble() shouldBeGreaterThan LIT_CODES
      frame.pixelAt(0, 0).isNear(Rgb.Black, CLEAR_CODES) shouldBe true
      frame.pixelAt(WIDTH - 1, HEIGHT - 1).isNear(Rgb.Black, CLEAR_CODES) shouldBe true
      picture.pixelAt(0, 0).red.toDouble() shouldBeGreaterThan LIT_CODES
      worstCodes(frame, picture, crt) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun aBrightPictureStopsAtFullScale() =
    runTest {
      val crt = Crt(FULL_INTENSITY)
      val clip = flatClip(FULL_HD_WIDTH, FULL_HD_HEIGHT, WHITE)

      val frame = drawn(edit(Clip(clip), listOf(crt)))
      val picture = drawn(edit(Clip(clip)))

      val params = CrtParams.of(crt)
      val centre = FULL_HD_WIDTH / 2 to FULL_HD_HEIGHT / 2
      val crest =
        CENTRE_READS
          .flatMap { dy -> CENTRE_READS.map { dx -> centre.first + dx to centre.second + dy } }
          .first { (x, y) ->
            val source =
              CrtSignal.barrelMap(
                (x + 0.5) / FULL_HD_WIDTH,
                (y + 0.5) / FULL_HD_HEIGHT,
                FULL_HD_WIDTH,
                FULL_HD_HEIGHT,
                params.barrelK,
              )
            val unclamped =
              CrtSignal.scanlineAt(source[1], 1.0 / FULL_HD_HEIGHT, params.scanlineDepth) *
                CrtSignal.maskAt(source[0], 0, 1.0 / FULL_HD_WIDTH, params.maskDepth) * params.gain
            unclamped > PAST_FULL_SCALE
          }
      withClue("the fixture has to sit near the top of the code range") {
        picture.pixelAt(crest.first, crest.second).red.toDouble() shouldBeGreaterThan WHITE_READ_CODES
      }

      frame.pixelAt(crest.first, crest.second).red shouldBe FULL.toInt()
    }

  @Test
  fun aSmallerRenderIsTheLargerRenderBoxedDownAtEvenAndAtWideFactors() =
    runTest {
      assertBoxedDown(LARGE_WIDTH, LARGE_HEIGHT, factor = 2)
      assertBoxedDown(FULL_HD_WIDTH, FULL_HD_HEIGHT, factor = 3)
    }

  private suspend fun assertLandsOnTheSignal(
    width: Int,
    height: Int,
    composition: (MediaSource, Crt) -> Composition,
  ) {
    val crt = Crt(MID)
    val clip = gradientClip(width, height)

    val frame = drawn(composition(clip, crt))
    val picture = drawn(edit(Clip(clip)))

    frame.size.width shouldBe width
    withClue("the look has to move the frame well past the tolerance for this to tell") {
      worstChange(frame, picture) shouldBeGreaterThan EFFECT_CODES
    }
    worstCodes(frame, picture, crt) shouldBeLessThan SIGNAL_CODES
  }

  private suspend fun assertBoxedDown(
    width: Int,
    height: Int,
    factor: Int,
  ) {
    val crt = Crt(MID)
    val clip = flatClip(width, height, GREY)
    val small = ExportSpec(heightPx = height / factor, videoCodec = VideoCodec.H264)

    val large = drawn(edit(Clip(clip), listOf(crt)))
    val preview = drawn(edit(Clip(clip), listOf(crt)), small)

    preview.size.width shouldBe width / factor
    preview.size.height shouldBe height / factor
    var worst = 0.0
    var worstPoint = 0.0
    for (row in parityReads(preview.size.height)) {
      for (column in parityReads(preview.size.width)) {
        val read = preview.pixelAt(column, row).channels()
        val boxed = large.boxAt(column * factor, row * factor, factor)
        val point = large.pixelAt(column * factor, row * factor).channels()
        for (channel in 0 until RGB) {
          worst = max(worst, abs(read[channel] - boxed[channel]) * FULL)
          worstPoint = max(worstPoint, abs(read[channel] - point[channel]) * FULL)
        }
      }
    }
    withClue("a nearest read of the large frame has to miss for this to tell, it reads $worstPoint") {
      worstPoint shouldBeGreaterThan PARITY_CODES * 2
    }
    withClue("$width by $height at a factor of $factor") { worst shouldBeLessThan PARITY_CODES }
  }

  // A spread over the frame, and every pixel near a side where the curve's edge crosses a small pixel.
  private fun parityReads(size: Int): List<Int> {
    val margin = size / PARITY_MARGIN_OF_SIZE
    return ((0 until size step PARITY_STRIDE) + (0 until margin) + (size - margin until size)).distinct()
  }

  private fun worstChange(
    frame: PreviewFrame,
    picture: PreviewFrame,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    var worst = 0.0
    (-MIDDLE_READS..MIDDLE_READS).forEach { offset ->
      val read = frame.pixelAt(width / 2 + offset, height / 2 + offset).channels()
      val plain = picture.pixelAt(width / 2 + offset, height / 2 + offset).channels()
      for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - plain[channel]) * FULL)
    }
    return worst
  }

  private fun PreviewFrame.boxAt(
    column: Int,
    row: Int,
    factor: Int,
  ): DoubleArray {
    val sum = DoubleArray(RGB)
    for (y in row until row + factor) {
      for (x in column until column + factor) {
        val channels = pixelAt(x, y).channels()
        for (channel in 0 until RGB) sum[channel] += channels[channel]
      }
    }
    return DoubleArray(RGB) { sum[it] / (factor * factor) }
  }
}

private val GREY = Rgb(120, 120, 120)
private val BRIGHT = Rgb(190, 185, 180)
private val WHITE = Rgb(255, 255, 255)
private val CENTRE_READS = -4..4

private const val MID = 0.5f
private const val FULL_INTENSITY = 1f
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 1280
private const val HEIGHT = 720
private const val LARGE_WIDTH = 1280
private const val LARGE_HEIGHT = 720
private const val FULL_HD_WIDTH = 1920
private const val FULL_HD_HEIGHT = 1080
private const val UNEVEN_WIDTH = 1366
private const val UNEVEN_HEIGHT = 768
private const val MIDDLE_READS = 6
private const val PARITY_STRIDE = 7
private const val PARITY_MARGIN_OF_SIZE = 16

// A lit pixel of the bright fixture, well off black after the darkest of the look at full intensity.
private const val LIT_CODES = 100.0

// The product of the patterns and the gain that puts a white read past the top of the code range, whichever way the
// fixture's white rounds, and that white.
private const val PAST_FULL_SCALE = 1.02
private const val WHITE_READ_CODES = 250.0

// A read and the picture it is held against each round to one eight bit code. Measured at 0.53.
private const val SIGNAL_CODES = 1.0

// Well past SIGNAL_CODES, so a read landing inside it says the look is there.
private const val EFFECT_CODES = 6.0

// A small frame and a box of a large one each round, the footprint leaves the curve's stretch out, and a box the
// curve's edge crosses weighs the pattern under it unevenly. Measured at 2.5 on such a box, and 1.5 away from one.
private const val PARITY_CODES = 3.0

// A fill through an eight bit canvas.
private const val CLEAR_CODES = 1
