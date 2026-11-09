package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Color
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.assumeDecodesTenBitHevc
import dev.jordond.filmstrip.test.assumeEncodesHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

/**
 * The letterbox fill, checked against the pixels a real export writes.
 *
 * Every clip here is 16:9, and forcing a square output with [Crop] leaves top and bottom bars with
 * nothing else changing the frame's aspect. That is what turns a fill into something a pixel can
 * confirm rather than only the plan.
 */
class AndroidFillMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aSolidFillWritesBarsOfItsOwnColor() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val written = magentaExport(source, listOf(squareCrop()))

      val bar = written.averageAt(CENTRE_X, BAR_Y)
      withClue("bar $bar is not magenta") { (colorDistance(bar, MAGENTA) < COLOR_TOLERANCE) shouldBe true }
    }

  // The fill-paint-order regression: a composition-scope grade must not reach a bar it was not
  // given a color for, whatever it does to the frame it sits around.
  @Test
  fun aCompositionBrightnessLeavesASolidFillsBarsUntouched() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val plain = magentaExport(source, listOf(squareCrop()))
      val dimmed = magentaExport(source, listOf(squareCrop(), Brightness(0.5f)))

      val bar = dimmed.averageAt(CENTRE_X, BAR_Y)
      withClue("bar $bar is not magenta") { (colorDistance(bar, MAGENTA) < COLOR_TOLERANCE) shouldBe true }

      val brightCentre = plain.averageAt(CENTRE_X, CENTRE_Y).luminance()
      val dimCentre = dimmed.averageAt(CENTRE_X, CENTRE_Y).luminance()
      withClue("the centre did not darken: $dimCentre against $brightCentre") {
        (dimCentre < brightCentre) shouldBe true
      }
    }

  // The same regression with a bias in the matrix, which is the half a brightness cannot cover. The
  // flatten mixes the fill under a pixel by that pixel's alpha, so a bar comes out the fill's own
  // color and never picks up the offset the contrast adds to the frame around it.
  @Test
  fun aCompositionContrastLeavesASolidFillsBarsUntouched() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val plain = magentaExport(source, listOf(squareCrop()))
      val graded = magentaExport(source, listOf(squareCrop(), Contrast(0.5f)))

      val bar = graded.averageAt(CENTRE_X, BAR_Y)
      withClue("bar $bar is not magenta") { (colorDistance(bar, MAGENTA) < COLOR_TOLERANCE) shouldBe true }

      val before = plain.averageAt(COLOR_BAR_X, CENTRE_Y)
      val after = graded.averageAt(COLOR_BAR_X, CENTRE_Y)
      withClue("the frame inside the bars did not change: $after against $before") {
        (colorDistance(before, after) > GRADE_SHIFT) shouldBe true
      }
    }

  // The §5.1 regression: a color operation changes a pixel's RGB without touching its alpha, so a
  // letterbox bar cleared to black with zero alpha reads back grey once something downstream of
  // Presentation touches color. Tone-mapping an HDR source to SDR is exactly such an operation, and
  // it is the one every export with an HDR source and Fill.Black is required to run.
  @Test
  fun aBlackFillStaysBlackThroughToneMapping() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      // Tone mapping runs on a device with no HDR encoder, but not on one with no HDR decoder.
      assumeDecodesTenBitHevc()
      val bitmap =
        probe.frame(
          export(source, effects = listOf(squareCrop()), fill = Fill.Black, hdr = HdrMode.ToneMapToSdr),
        )

      val bar = bitmap.averageAt(CENTRE_X, BAR_Y)
      withClue("bar $bar is not black") {
        (bar.red < BLACK_CEILING && bar.green < BLACK_CEILING && bar.blue < BLACK_CEILING) shouldBe true
      }
    }

  @Test
  fun aBlurredFillIsNeitherBlackNorFlat() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val bitmap = probe.frame(export(source, effects = listOf(squareCrop()), fill = Fill.Blurred()))

      val left = bitmap.averageAt(LEFT_X, BAR_Y)
      val right = bitmap.averageAt(RIGHT_X, BAR_Y)

      withClue("blurred bar $left reads as black") {
        (left.red > BLACK_CEILING || left.green > BLACK_CEILING || left.blue > BLACK_CEILING) shouldBe true
      }
      withClue("two points in the blurred bar read the same: $left, $right") {
        (colorDistance(left, right) > FLAT_TOLERANCE) shouldBe true
      }
    }

  // dim is a multiply of the background's color channels, never an offset, so half dim has to land
  // the background halfway to black rather than at some other fraction. An endpoint check would not
  // catch a backend applying dim as an offset, since dim = 1f is black either way.
  @Test
  fun aMidRangeDimLandsMidRangeNotAtAnEndpoint() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val undimmed = probe.frame(export(source, effects = listOf(squareCrop()), fill = Fill.Blurred(dim = 0f)))
      val halfDimmed = probe.frame(export(source, effects = listOf(squareCrop()), fill = Fill.Blurred(dim = 0.5f)))

      val bright = undimmed.averageAt(CENTRE_X, BAR_Y).luminance()
      val half = halfDimmed.averageAt(CENTRE_X, BAR_Y).luminance()
      withClue("the undimmed bar reads as black, nothing to compare against") { (bright > BLACK_CEILING) shouldBe true }

      val ratio = half.toFloat() / bright
      withClue("half dim landed at $ratio of undimmed, not mid-range") { (ratio in DIM_RATIO_RANGE) shouldBe true }
    }

  @Test
  fun aBlurredFillsSharpCentreStillMatchesTheSource() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val plain = probe.frame(export(source, effects = emptyList(), fill = Fill.Black))
      val contained = probe.frame(export(source, effects = listOf(squareCrop()), fill = Fill.Blurred()))

      val distance = colorDistance(plain.averageAt(CENTRE_X, CENTRE_Y), contained.averageAt(CENTRE_X, CENTRE_Y))
      withClue("the sharp centre drifted from the source by $distance") { (distance < COLOR_TOLERANCE) shouldBe true }
    }

  // An HDR frame reaches an effect as linear light normalised to a thousand nits, where an sRGB
  // channel written straight in means several times the brightness it was authored at. Comparing two
  // fills rather than reading one absolute value is what survives the tone mapping the retriever
  // applies on the way back out.
  @Test
  fun aSolidFillOnHdrIsWrittenAtGraphicsWhiteNotAtThePanelPeak() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val white = probe.frame(export(source, listOf(squareCrop()), Fill.White, HdrMode.KeepHdr))
      val grey = probe.frame(export(source, listOf(squareCrop()), Fill.Solid(GREY_ARGB), HdrMode.KeepHdr))

      val bright = white.averageAt(CENTRE_X, BAR_Y).luminance()
      val mid = grey.averageAt(CENTRE_X, BAR_Y).luminance()
      withClue("the white bar reads as black, nothing to compare against") { (bright > BLACK_CEILING) shouldBe true }

      val ratio = mid.toFloat() / bright
      withClue("a mid grey landed at $ratio of white, so the fill skipped its conversion") {
        (ratio < HDR_GREY_CEILING) shouldBe true
      }
    }

  @Test
  fun aBlackFillStaysBlackWhenHdrIsKept() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val kept = probe.frame(export(source, listOf(squareCrop()), Fill.Black, HdrMode.KeepHdr))
      val bar = kept.averageAt(CENTRE_X, BAR_Y)
      withClue("bar $bar is not black on an export that kept its HDR grade") {
        (bar.red < BLACK_CEILING && bar.green < BLACK_CEILING && bar.blue < BLACK_CEILING) shouldBe true
      }
    }

  private fun squareCrop(): EffectSpec = Crop(AspectRatio.Square, fit = Fit.Contain)

  private suspend fun export(
    source: MediaSource,
    effects: List<EffectSpec>,
    fill: Fill,
    hdr: HdrMode = HdrMode.Auto,
  ): File {
    val composition = Composition(listOf(Track(listOf(Clip(source)))), effects, fill = fill)
    return probe.export(composition, ExportSpec(hdr = hdr))
  }

  // A magenta fill export, read back as the codes its encoder wrote so the bar's color is exact.
  private suspend fun magentaExport(
    source: MediaSource,
    effects: List<EffectSpec>,
  ): Written {
    val composition = Composition(listOf(Track(listOf(Clip(source)))), effects, fill = Fill.Solid(MAGENTA_ARGB))
    return probe.written(composition, ExportSpec())
  }

  private fun Written.averageAt(
    fx: Float,
    fy: Float,
  ): Rgb {
    val (red, green, blue) = rgbAt(fx, fy)
    return Rgb(red, green, blue)
  }

  /**
   * The average color of a small patch centred at the fractional coordinate ([fx], [fy]).
   */
  private fun Bitmap.averageAt(
    fx: Float,
    fy: Float,
  ): Rgb {
    val left = ((width - PATCH) * fx).toInt().coerceIn(0, width - PATCH)
    val top = ((height - PATCH) * fy).toInt().coerceIn(0, height - PATCH)
    var red = 0
    var green = 0
    var blue = 0
    for (x in left until left + PATCH) {
      for (y in top until top + PATCH) {
        val pixel = getPixel(x, y)
        red += Color.red(pixel)
        green += Color.green(pixel)
        blue += Color.blue(pixel)
      }
    }
    val pixels = PATCH * PATCH
    return Rgb(red / pixels, green / pixels, blue / pixels)
  }

  private fun colorDistance(
    a: Rgb,
    b: Rgb,
  ): Int = kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)

  private class Rgb(
    val red: Int,
    val green: Int,
    val blue: Int,
  ) {
    fun luminance(): Int = red + green + blue

    override fun toString(): String = "($red, $green, $blue)"
  }

  private companion object {
    const val PATCH = 8

    // Both safely inside the bar a square crop of a 16:9 clip leaves along its top edge.
    const val BAR_Y = 0.05f
    const val CENTRE_X = 0.5f
    const val CENTRE_Y = 0.5f
    const val LEFT_X = 0.15f
    const val RIGHT_X = 0.85f

    // The middle of one of the pattern's color bars, which a contrast has to move. The frame's own
    // centre sits on the seam between two of them and reads as a mid grey the pivot leaves alone.
    const val COLOR_BAR_X = 0.19f

    const val MAGENTA_ARGB = 0xFFFF00FF.toInt()
    const val GREY_ARGB = 0xFF808080.toInt()
    val MAGENTA = Rgb(255, 0, 255)

    const val BLACK_CEILING = 12
    const val COLOR_TOLERANCE = 30
    const val GRADE_SHIFT = 60
    const val FLAT_TOLERANCE = 20
    val DIM_RATIO_RANGE = 0.35f..0.65f

    // A mid grey is 43.8 nits against white's 203, and the Fold 7 measures the pair at 0.52 once
    // the retriever has tone mapped them back. Writing sRGB straight into media3's linear space
    // would put them at 500 and 1000 nits instead, which lands near 0.73, so the bound sits between.
    const val HDR_GREY_CEILING = 0.62f
  }
}
