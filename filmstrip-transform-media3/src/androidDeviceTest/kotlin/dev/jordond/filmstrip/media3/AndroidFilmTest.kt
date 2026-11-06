package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaFormat
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.HalationGlow
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.test.Test

/**
 * Where a [Film] lands in a real export: what it reaches at each scope, and what stays clean.
 *
 * Every read here is of the glow, whose figure is large enough to survive an encoder, and is held against the signal
 * worked over the same read with no [Film]. How close each pixel lands to the signal, grain included, is read off the
 * shader's own output in the filters module.
 *
 * A tone mapped HDR source cannot run on the emulator, which has no ten-bit HEVC decoder. That test says so and skips
 * there, and runs on a device that has one.
 */
class AndroidFilmTest {
  private val probe = Media3ExportProbe { filters() }

  @Test
  fun aCompositionFilmGlowsThePictureOnTheSignal() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip("film-mid.mp4", MID_RESPONSE))

      assertGlowsOnTheSignal(EditComposition(listOf(Track(listOf(clip))), listOf(GLOW_ONLY)), clip)
    }

  @Test
  fun aClipFilmGlowsThePictureOnTheSignal() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip("film-mid.mp4", MID_RESPONSE)

      assertGlowsOnTheSignal(
        EditComposition(listOf(Track(listOf(Clip(source, effects = listOf(GLOW_ONLY)))))),
        Clip(source),
      )
    }

  @Test
  fun aSmallerExportReadsTheSameGlow() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip("film-mid.mp4", MID_RESPONSE))

      assertGlowsOnTheSignal(
        EditComposition(listOf(Track(listOf(clip))), listOf(GLOW_ONLY)),
        clip,
        ExportSpec(heightPx = SMALL_HEIGHT),
      )
    }

  @Test
  fun aCompositionFilmGlowsFromThePictureIntoABar() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = flatClip("film-bright.mp4", BRIGHT)
      val edit = { effects: List<EffectSpec> ->
        EditComposition(listOf(Track(listOf(Clip(source)))), effects, fill = Fill.Solid(BLACK))
      }

      val spec = ExportSpec()
      val plan = probe.plan(edit(listOf(crop, GLOW_ONLY)), spec)
      val written = probe.writtenBy(probe.finish(plan))
      val plain = probe.writtenBy(probe.finish(probe.plan(edit(listOf(crop)), spec)))

      val size = plan.output.size
      val rect = placementOf(SOURCE, listOf(listOf(crop)), size).drawnRect
      val glow =
        HalationGlow.of(size.width, size.height) { x, y ->
          val inside = (x + 0.5) / size.width in rect.left..rect.right
          if (inside) doubleArrayOf(BRIGHT, BRIGHT, BRIGHT) else doubleArrayOf(0.0, 0.0, 0.0)
        }
      val plan2 = FilmSignal.halationBlurPlan(size.width)
      val fx = rect.right + (plan2.sigmaPx * plan2.factor / size.width).toFloat()
      val x = (fx * size.width).toInt()
      val y = size.height / 2
      val wanted = FilmSignal.halated(0.0, 0.0, 0.0, glow.at(x, y), FilmParams.of(GLOW_ONLY))[0] * MAX_CODE
      val lift = (written.rgbAt(fx, 0.5f).first - plain.rgbAt(fx, 0.5f).first).toDouble()

      withClue("the glow has to clear what an encoder moves for this to tell, $wanted codes") {
        wanted shouldBeGreaterThan ENCODER_CODES * 2
      }
      withClue("one sigma into the bar lifted $lift against $wanted") {
        abs(lift - wanted) shouldBeLessThan ENCODER_CODES
      }
    }

  @Test
  fun aClipFilmLeavesACanvasBarAsItsFill() =
    runTest(timeout = TIMEOUT) {
      assertBarClean(Fill.Solid(BAR_FILL), Fill.Solid(BAR_FILL))
    }

  // A blurred fill turns a canvas's bars black, so the same crop over a black fill is what they should read.
  @Test
  fun aClipFilmLeavesABlurredFillsCanvasBarsBlack() =
    runTest(timeout = TIMEOUT) {
      assertBarClean(Fill.Blurred(), Fill.Solid(BLACK))
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip("film-dark.mp4", DARK)
      val swatch = swatchOverlay()
      val filmed = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(swatch, GLOW_ONLY))
      val plain = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(swatch))

      val read = redOfSwatch(filmed)
      val wanted = redOfSwatch(plain)

      withClue("the swatch read $read against $wanted") { abs(read - wanted) shouldBeLessThan ENCODER_CODES }
    }

  @Test
  fun aClipOverlayUnderACompositionFilmIsBurned() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip("film-dark.mp4", DARK), effects = listOf(swatchOverlay()))
      val filmed = EditComposition(listOf(Track(listOf(clip))), listOf(GLOW_ONLY))
      val plain = EditComposition(listOf(Track(listOf(clip))))

      val read = redOfSwatch(filmed)
      val under = redOfSwatch(plain) / MAX_CODE
      val response = FilmSignal.halationResponse(under, under, under)
      val wanted = FilmSignal.halated(under, under, under, response, FilmParams.of(GLOW_ONLY))[0] * MAX_CODE

      withClue("the glow has to clear what an encoder moves for this to tell") {
        (wanted - under * MAX_CODE) shouldBeGreaterThan ENCODER_CODES * 2
      }
      withClue("the swatch read $read against $wanted") { abs(read - wanted) shouldBeLessThan ENCODER_CODES }
    }

  @Test
  fun aToneMappedHdrSourceExportsUnderAFilm() =
    runTest(timeout = TIMEOUT) {
      assumeTrue("this device has no ten-bit HEVC decoder, which is every emulator", decodesTenBitHevc())
      val edit = EditComposition(listOf(Track(listOf(Clip(probe.fixture(HDR_CLIP))))), listOf(Film()))

      val finished = probe.finish(probe.plan(edit))

      finished.adjustments.any { it.kind == AdjustmentKind.HdrToneMapped } shouldBe true
    }

  private suspend fun assertGlowsOnTheSignal(
    edit: EditComposition,
    clip: Clip,
    spec: ExportSpec = ExportSpec(),
  ) {
    val read = probe.written(edit, spec).rgbAt(0.5f, 0.5f)
    val plain = probe.written(EditComposition(listOf(Track(listOf(clip)))), spec).rgbAt(0.5f, 0.5f)
    val code = plain.first / MAX_CODE
    val response = FilmSignal.halationResponse(code, code, code)
    val wanted = FilmSignal.halated(code, code, code, response, FilmParams.of(GLOW_ONLY))[0] * MAX_CODE

    withClue("the glow has to clear what an encoder moves for this to tell, $wanted codes over ${plain.first}") {
      (wanted - plain.first) shouldBeGreaterThan ENCODER_CODES * 2
    }
    withClue("the picture read ${read.first} against $wanted") {
      abs(read.first - wanted) shouldBeLessThan ENCODER_CODES
    }
  }

  // Reads a bar just off a bright picture under a clip Film against the same crop over [plainFill] with no Film.
  private suspend fun assertBarClean(
    fill: Fill,
    plainFill: Fill,
  ) {
    val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
    val source = flatClip("film-bright.mp4", BRIGHT)
    val edit = { effects: List<EffectSpec>, over: Fill ->
      EditComposition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = over)
    }
    val spec = ExportSpec()
    val plan = probe.plan(edit(listOf(crop, Film(grain = 1f, halation = 1f)), fill), spec)
    val written = probe.writtenBy(probe.finish(plan))
    val plain = probe.writtenBy(probe.finish(probe.plan(edit(listOf(crop), plainFill), spec)))
    val rect = placementOf(SOURCE, listOf(listOf(crop)), plan.output.size).drawnRect

    // One sigma off the picture, where a glow left in the bar is at its widest clear of the picture's own edge.
    val blur = FilmSignal.halationBlurPlan(plan.output.size.width)
    val fx = rect.right + (blur.sigmaPx * blur.factor / plan.output.size.width).toFloat()
    val read = written.rgbAt(fx, 0.5f)
    val wanted = plain.rgbAt(fx, 0.5f)
    withClue("the bar read $read against $wanted") {
      abs(read.first - wanted.first).toDouble() shouldBeLessThan BAR_CODES
      abs(read.second - wanted.second).toDouble() shouldBeLessThan BAR_CODES
      abs(read.third - wanted.third).toDouble() shouldBeLessThan BAR_CODES
    }
  }

  private suspend fun flatClip(
    name: String,
    code: Double,
  ): MediaSource {
    val file = File(probe.context.cacheDir, name)
    val grey = code.toFloat()
    if (!file.exists()) probe.writeBarsClip(file, SOURCE.width, SOURCE.height, listOf(floatArrayOf(grey, grey, grey)))
    return MediaSource.of(file.path)
  }

  private suspend fun redOfSwatch(edit: EditComposition): Double =
    probe
      .written(edit, ExportSpec())
      .rgbAt(SWATCH_FX, SWATCH_FY)
      .first
      .toDouble()

  private fun swatchOverlay(): ImageOverlay {
    val file = File(probe.context.cacheDir, "film-swatch.png")
    if (!file.exists()) {
      val bitmap = Bitmap.createBitmap(SWATCH_PX, SWATCH_PX, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(SWATCH_COLOR)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return ImageOverlay(ImageSource.of(file.path), Corner.TopStart, scale = SWATCH_SCALE)
  }

  private fun decodesTenBitHevc(): Boolean {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720)
    format.setInteger(MediaFormat.KEY_PROFILE, HEVC_MAIN_10)
    return MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
  }

  private companion object {
    val GLOW_ONLY = Film(grain = 0f, halation = 1f)
    val SOURCE = Size(640, 360)
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val MAX_CODE = 255.0
    const val BLACK = 0xFF000000.toInt()
    const val BAR_FILL = 0xFF2F4F6F.toInt()
    const val SMALL_HEIGHT = 240
    const val HEVC_MAIN_10 = 2

    // Sits on the response's ramp, so the glow neither vanishes nor clamps.
    const val MID_RESPONSE = 0.8
    const val BRIGHT = FilmSignal.HALATION_FULL_LUMA
    const val DARK = 0.2

    // The swatch is on the response's ramp too, and the clip under it gives no light of its own.
    const val SWATCH_COLOR = 0xFFCCCCCC.toInt()
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f

    // Two H.264 encodes of one flat frame move a colour by a few codes on their own.
    const val ENCODER_CODES = 5.0

    // The same bar written twice, with nothing drawn in it either time.
    const val BAR_CODES = 2.0
  }
}
