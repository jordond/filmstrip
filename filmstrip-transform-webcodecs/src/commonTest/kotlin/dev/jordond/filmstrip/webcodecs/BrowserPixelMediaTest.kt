package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.pixel.PixelPalette
import dev.jordond.filmstrip.effects.filters.pixel.PixelSignal
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fill
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

/**
 * A [Pixel] drawn through the real browser compositor, read against [PixelSignal.pixelAt].
 *
 * The picture a read is held against is the same clip drawn with no [Pixel], read at whole pixels, so what the
 * fixture's encoder and the decoder did to it is in the expected value as well.
 */
class BrowserPixelMediaTest : BrowserCrtFixture() {
  @Test
  fun aCompositionPixelLandsOnTheSignalWithLevelsAndDither() =
    runTest {
      assertLandsOnTheSignal(LEVELS) { clip, pixel -> edit(Clip(clip), listOf(pixel)) }
    }

  @Test
  fun aClipPixelLandsOnTheSignalWithLevelsAndDither() =
    runTest {
      assertLandsOnTheSignal(LEVELS) { clip, pixel -> edit(Clip(clip, effects = listOf(pixel))) }
    }

  @Test
  fun aPaletteLandsOnTheSignal() =
    runTest {
      assertLandsOnTheSignal(PALETTE) { clip, pixel -> edit(Clip(clip), listOf(pixel)) }
    }

  @Test
  fun aSixteenColourPaletteLandsOnTheSignal() =
    runTest {
      assertLandsOnTheSignal(SIXTEEN) { clip, pixel -> edit(Clip(clip), listOf(pixel)) }
    }

  @Test
  fun clipsWithDifferentPalettesEachDrawTheirOwn() =
    runTest {
      val clip = gradientClip(WIDTH, HEIGHT)
      val looks = listOf(PALETTE, Pixel(cells = CELLS, dither = 0.5f, palette = PixelPalette.Cga), LEVELS)
      val composition =
        Composition(
          tracks = listOf(Track(looks.map { Clip(clip, effects = listOf(it)) })),
          fill = Fill.Solid(BLACK_ARGB),
          audio = AudioSpec.Remove,
        )
      val picture = drawn(edit(Clip(clip)))

      val preview = previewOf(composition, SPEC)
      try {
        looks.forEachIndexed { index, look ->
          val frame = preview.frameAt(outputSlotTime(index * CLIP_FRAMES + READ_SLOT, FRAME_RATE)).shouldNotBeNull()
          withClue("clip $index") { worstCodes(frame, picture, look) shouldBeLessThan SIGNAL_CODES }
        }
      } finally {
        preview.release()
      }
    }

  @Test
  fun aSmallerRenderLandsOnTheSameCells() =
    runTest {
      val clip = gradientClip(WIDTH, HEIGHT)
      val small = ExportSpec(heightPx = HEIGHT / 2, videoCodec = VideoCodec.H264)

      val frame = drawn(edit(Clip(clip), listOf(LEVELS)), small)
      val picture = drawn(edit(Clip(clip)), small)

      frame.size.height shouldBe HEIGHT / 2
      PixelSignal.columns(frame.size.width, frame.size.height, LEVELS.cells) shouldBe
        PixelSignal.columns(WIDTH, HEIGHT, LEVELS.cells)
      worstCodes(frame, picture, LEVELS) shouldBeLessThan SIGNAL_CODES
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest {
      val clip = flatClip(WIDTH, HEIGHT, GREY)
      val twoLevels = Pixel(cells = CELLS, levels = 2)

      val frame = drawn(edit(Clip(clip), listOf(swatch(), twoLevels)))

      withClue("the look has to cut the grey down to black for this to tell") {
        frame.pixelAt(WIDTH / 2, HEIGHT / 2).isNear(Rgb.Black, CLEAR_CODES) shouldBe true
      }
      frame.at(SWATCH_X, SWATCH_Y).isNear(Rgb(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE), CLEAR_CODES) shouldBe true
    }

  private suspend fun assertLandsOnTheSignal(
    pixel: Pixel,
    composition: (MediaSource, Pixel) -> Composition,
  ) {
    val clip = gradientClip(WIDTH, HEIGHT)

    val frame = drawn(composition(clip, pixel))
    val picture = drawn(edit(Clip(clip)))

    frame.size.width shouldBe WIDTH
    withClue("the look has to move the frame well past the tolerance for this to tell") {
      worstChange(frame, picture) shouldBeGreaterThan EFFECT_CODES
    }
    worstCodes(frame, picture, pixel) shouldBeLessThan SIGNAL_CODES
  }

  // The largest gap between the frame and the signal over a spread of rows and columns that crosses many cell edges,
  // in eight bit codes.
  private fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    pixel: Pixel,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    var worst = 0.0
    reads(height).forEach { y ->
      reads(width).forEach { x ->
        val expected =
          PixelSignal.pixelAt(
            x,
            y,
            width,
            height,
            pixel,
          ) { column, row -> picture.pixelAt(column, row).channels() }
        val read = frame.pixelAt(x, y).channels()
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - expected[channel]) * FULL)
      }
    }
    return worst
  }

  private fun worstChange(
    frame: PreviewFrame,
    picture: PreviewFrame,
  ): Double {
    var worst = 0.0
    reads(frame.size.height).forEach { y ->
      val read = frame.pixelAt(frame.size.width / 3, y).channels()
      val plain = picture.pixelAt(frame.size.width / 3, y).channels()
      for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - plain[channel]) * FULL)
    }
    return worst
  }

  // Every pixel through the middle, so each cell edge there is crossed, plus a spread out to both sides.
  private fun reads(size: Int): List<Int> =
    ((size / 2 - MIDDLE_READS until size / 2 + MIDDLE_READS) + (0 until size step SPREAD_STRIDE) + (size - 1))
      .distinct()
}

private val LEVELS = Pixel(cells = 90, levels = 6, dither = 0.5f)
private val PALETTE = Pixel(cells = 90, dither = 0.5f, palette = PixelPalette.GameBoy)

// A full palette on a diagonal, so the gradient crosses many of its entries.
private val SIXTEEN =
  Pixel(
    cells = 90,
    dither = 0.5f,
    palette = PixelPalette(List(PixelPalette.MAX_COLORS) { (it * 0x0F0B07 + 0x081018) and 0xFFFFFF }),
  )
private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private val GREY = Rgb(120, 120, 120)
private const val CELLS = 90
private const val BLACK_ARGB = 0xFF000000.toInt()

// The fixture's clip length and frame rate, and the frame it reads inside each clip.
private const val CLIP_FRAMES = 12L
private const val FRAME_RATE = 30
private const val READ_SLOT = 6L
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 1280
private const val HEIGHT = 720
private const val MIDDLE_READS = 24
private const val SPREAD_STRIDE = 37
private const val SWATCH_CODE = 0x20
private const val SWATCH_X = 0.15
private const val SWATCH_Y = 0.2

// A step lands on a whole code, so a read that took the right cell and step is off by at most its own rounding.
private const val SIGNAL_CODES = 1.0

// Well past SIGNAL_CODES, so a read landing inside it says the look is there.
private const val EFFECT_CODES = 6.0

// A fill through an eight bit canvas.
private const val CLEAR_CODES = 1
