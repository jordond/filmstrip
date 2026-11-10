package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.nightvision.NightVisionParams
import dev.jordond.filmstrip.effects.filters.nightvision.NightVisionSignal
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A [NightVision] drawn through the real browser compositor, read against [NightVisionSignal.pixelAt].
 *
 * The picture a read is held against is the same clip drawn with no [NightVision], so what the fixture's encoder and
 * the decoder did to it is in the expected value as well.
 */
class BrowserNightVisionMediaTest : BrowserCrtFixture() {
  @Test
  fun aCompositionLookLandsOnTheSignalAtHalfIntensity() =
    runTest {
      assertLandsOnTheSignal { clip -> edit(Clip(clip), listOf(HALF)) }
    }

  @Test
  fun aClipLookLandsOnTheSignalAtHalfIntensity() =
    runTest {
      assertLandsOnTheSignal { clip -> edit(Clip(clip, effects = listOf(HALF))) }
    }

  @Test
  fun aSmallerRenderDrawsTheSameLookAtItsOwnSize() =
    runTest {
      val clip = gradientClip(WIDTH, HEIGHT)
      val small = ExportSpec(heightPx = HEIGHT / 2, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(HALF)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe HEIGHT / 2
      worstCodes(frame, picture, HALF) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun theSameTimeDrawsTheSamePictureAfterASeek() =
    runTest {
      val preview = previewOf(edit(Clip(gradientClip(WIDTH, HEIGHT)), listOf(HALF)), SPEC)
      try {
        val first = preview.frameAt(DRAWN_READ_TIME).shouldNotBeNull()
        val elsewhere = preview.frameAt(Duration.ZERO).shouldNotBeNull()
        val again = preview.frameAt(DRAWN_READ_TIME).shouldNotBeNull()

        withClue("the grain moves between the two times") {
          elsewhere.pixels.contentEquals(first.pixels) shouldBe false
        }
        withClue("the same time draws the same picture") { again.pixels.contentEquals(first.pixels) shouldBe true }
      } finally {
        preview.release()
      }
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest {
      val frame = drawn(edit(Clip(flatClip(WIDTH, HEIGHT, GREY)), listOf(swatch(), NightVision())))

      frame.at(SWATCH_X, SWATCH_Y).isNear(Rgb(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE), CLEAR_CODES) shouldBe true
    }

  private suspend fun assertLandsOnTheSignal(composition: (MediaSource) -> Composition) {
    val clip = gradientClip(WIDTH, HEIGHT)

    val frame = drawn(composition(clip))
    val picture = drawn(edit(Clip(clip)))

    frame.size.width shouldBe WIDTH
    withClue("the look has to move the frame well past the tolerance for this to tell") {
      val read = frame.pixelAt(WIDTH / 2, HEIGHT / 2).red
      val plain = picture.pixelAt(WIDTH / 2, HEIGHT / 2).red
      abs(read - plain).toDouble() shouldBeGreaterThan EFFECT_CODES
    }
    worstCodes(frame, picture, HALF) shouldBeLessThan SIGNAL_CODES
  }

  // The largest gap between the frame and the signal over a grid from edge to edge, in eight bit codes.
  private fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    nightVision: NightVision,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    val params = NightVisionParams.of(nightVision)
    var worst = 0.0
    reads(height).forEach { y ->
      reads(width).forEach { x ->
        val shown = picture.pixelAt(x, y).channels()
        val expected = NightVisionSignal.pixelAt(shown[0], shown[1], shown[2], x, y, width, height, TICK, params)
        val read = frame.pixelAt(x, y).channels()
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - expected[channel]) * FULL)
      }
    }
    return worst
  }

  private fun reads(size: Int): List<Int> = ((0 until size step SPREAD_STRIDE) + (size / 2) + (size - 1)).distinct()
}

private val HALF = NightVision(intensity = 0.5f, seed = 7)
private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private val GREY = Rgb(120, 120, 120)
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 1280
private const val HEIGHT = 720
private val TICK: Int = FilmSignal.grainTickAt(DRAWN_READ_TIME.toDouble(DurationUnit.SECONDS))
private const val SPREAD_STRIDE = 23
private const val SWATCH_CODE = 0x20
private const val SWATCH_X = 0.15
private const val SWATCH_Y = 0.2

// A read and the picture it is held against each round to one eight bit code.
private const val SIGNAL_CODES = 1.5

// Well past SIGNAL_CODES, so a read landing past it says the look is there.
private const val EFFECT_CODES = 6.0

// A fill through an eight bit canvas.
private const val CLEAR_CODES = 1
