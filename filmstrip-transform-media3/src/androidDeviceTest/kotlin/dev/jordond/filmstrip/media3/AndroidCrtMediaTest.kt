package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.CrtParams
import dev.jordond.filmstrip.effects.filters.crt.CrtSignal
import dev.jordond.filmstrip.effects.filters.filters
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
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.writeAtomically
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test

/**
 * Where a [Crt] lands in a real export: what it reaches at each scope, and what stays clean.
 *
 * Reads here are of what survives an encoder, the frame's falloff and the black past the curve, held against the signal
 * worked over the same read with no [Crt]. How close each pixel lands, the patterns included, is read off the shader's
 * own output in the filters module.
 */
class AndroidCrtMediaTest {
  private val probe = Media3ExportProbe { filters() }

  @Test
  fun aCompositionCrtDrawsTheFallOffOnTheSignal() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip())
      assertOnTheSignal(Composition(listOf(Track(listOf(clip))), listOf(Crt())), clip)
    }

  @Test
  fun aClipCrtDrawsTheFallOffOnTheSignal() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip())
      assertOnTheSignal(Composition(listOf(Track(listOf(Clip(flatClip(), effects = listOf(Crt())))))), clip)
    }

  @Test
  fun aSmallerExportReadsTheSameFallOff() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip())
      val spec = ExportSpec(heightPx = SMALL_HEIGHT)
      assertOnTheSignal(Composition(listOf(Track(listOf(clip))), listOf(Crt())), clip, spec)
    }

  @Test
  fun thePictureEndsInBlackPastTheCurve() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip())
      val read = probe.written(Composition(listOf(Track(listOf(clip))), listOf(Crt())), ExportSpec())

      withClue("a corner sits past the curve at full intensity") {
        CrtSignal.coverageAt(
          CORNER_FX.toDouble(),
          CORNER_FY.toDouble(),
          SOURCE.width,
          SOURCE.height,
          FULL.barrelK,
        ) shouldBe
          0.0
      }
      read.rgbAt(CORNER_FX, CORNER_FY).first.toDouble() shouldBeLessThan ENCODER_CODES
    }

  @Test
  fun aClipCrtLeavesACanvasBarAsItsFill() =
    runTest(timeout = TIMEOUT) {
      assertBarClean(Fill.Solid(BAR_FILL), Fill.Solid(BAR_FILL))
    }

  // A blurred fill turns a canvas's bars black, so the same crop over a black fill is what they should read.
  @Test
  fun aClipCrtLeavesABlurredFillsCanvasBarsBlack() =
    runTest(timeout = TIMEOUT) {
      assertBarClean(Fill.Blurred(), Fill.Solid(BLACK))
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()
      val swatch = swatchOverlay()
      val looked = Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch, Crt()))
      val plain = Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch))

      val read = redOfSwatch(looked)
      val wanted = redOfSwatch(plain)

      withClue("the swatch read $read against $wanted") { abs(read - wanted) shouldBeLessThan ENCODER_CODES }
    }

  @Test
  fun aClipOverlayUnderACompositionCrtIsBurned() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip(), effects = listOf(swatchOverlay()))
      val looked = Composition(listOf(Track(listOf(clip))), listOf(Crt()))
      val plain = Composition(listOf(Track(listOf(clip))))

      val read = redOfSwatch(looked)
      val wanted = redOfSwatch(plain)

      withClue("the swatch read $read against $wanted") { abs(read - wanted) shouldBeGreaterThan ENCODER_CODES }
    }

  // A read off the middle of the frame's width near its edge, where the falloff has moved furthest with no curve yet.
  private suspend fun assertOnTheSignal(
    edit: Composition,
    clip: Clip,
    spec: ExportSpec = ExportSpec(),
  ) {
    val written = probe.written(edit, spec)
    val plain =
      probe
        .written(
          Composition(listOf(Track(listOf(clip)))),
          spec,
        ).rgbAt(READ_FX, READ_FY)
        .second
        .toDouble()
    val wanted =
      CrtSignal.vignetteAt(READ_FX.toDouble(), READ_FY.toDouble(), SOURCE.width, SOURCE.height, FULL.vignette) *
        FULL.gain *
        (1.0 - FULL.scanlineDepth / 2.0) * (1.0 - FULL.maskDepth / 2.0) * plain
    withClue("the look has to clear what an encoder moves for this to tell, $wanted codes over $plain") {
      abs(wanted - plain) shouldBeGreaterThan SIGNAL_CODES
    }
    val read = written.rgbAt(READ_FX, READ_FY).second.toDouble()
    withClue("the read $read against $wanted") { abs(read - wanted) shouldBeLessThan SIGNAL_CODES }
  }

  private suspend fun assertBarClean(
    fill: Fill,
    plainFill: Fill,
  ) {
    val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
    val source = flatClip()
    val edit = { effects: List<EffectSpec>, over: Fill ->
      Composition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = over)
    }
    val spec = ExportSpec()
    val plan = probe.plan(edit(listOf(crop, Crt()), fill), spec)
    val written = probe.writtenBy(probe.finish(plan))
    val plain = probe.writtenBy(probe.finish(probe.plan(edit(listOf(crop), plainFill), spec)))
    val rect = placementOf(SOURCE, listOf(listOf(crop)), plan.output.size).drawnRect

    // The left bar comes before the picture in the encoder's scan. A bar after it is predicted off the picture's edge
    // and carries a code of whatever the encoder made of that edge, so it reads the edge rather than the bar.
    val fx = rect.left / 2f
    val read = written.rgbAt(fx, 0.5f)
    val wanted = plain.rgbAt(fx, 0.5f)
    withClue("the bar read $read against $wanted") {
      abs(read.first - wanted.first).toDouble() shouldBeLessThan BAR_CODES
      abs(read.second - wanted.second).toDouble() shouldBeLessThan BAR_CODES
      abs(read.third - wanted.third).toDouble() shouldBeLessThan BAR_CODES
    }
  }

  private suspend fun flatClip(): MediaSource {
    val file = File(probe.context.cacheDir, "crt-bright.mp4")
    val grey = BRIGHT.toFloat()
    if (!file.exists()) probe.writeBarsClip(file, SOURCE.width, SOURCE.height, listOf(floatArrayOf(grey, grey, grey)))
    return MediaSource.of(file.path)
  }

  private suspend fun redOfSwatch(edit: Composition): Double =
    probe
      .written(edit, ExportSpec())
      .rgbAt(SWATCH_FX, SWATCH_FY)
      .first
      .toDouble()

  private fun swatchOverlay(): ImageOverlay {
    val file = File(probe.context.cacheDir, "crt-swatch.png")
    if (!file.exists()) {
      val bitmap = Bitmap.createBitmap(SWATCH_PX, SWATCH_PX, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(SWATCH_COLOR)
      file.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return ImageOverlay(ImageSource.of(file.path), Corner.TopStart, scale = SWATCH_SCALE)
  }

  private companion object {
    val FULL = CrtParams.at(1.0)
    val SOURCE = Size(640, 360)
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val BLACK = 0xFF000000.toInt()
    const val BAR_FILL = 0xFF2F4F6F.toInt()
    const val SMALL_HEIGHT = 240
    const val BRIGHT = 0.7

    // Far enough in for a whole read patch to land on the frame, with all of it still past the curve.
    const val CORNER_FX = 0.012f
    const val CORNER_FY = 0.02f
    const val READ_FX = 0.5f
    const val READ_FY = 0.15f

    const val SWATCH_COLOR = 0xFFCCCCCC.toInt()
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f

    // What an encoder moves a flat read by.
    const val ENCODER_CODES = 5.0
    const val BAR_CODES = 2.0

    // An encoder over a pattern it softens, on a read of the pattern's mean.
    const val SIGNAL_CODES = 8.0
  }
}
