package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Color
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.digicam.Digicam
import dev.jordond.filmstrip.effects.filters.digicam.DigicamParams
import dev.jordond.filmstrip.effects.filters.digicam.DigicamSignal
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
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Where a [Digicam] lands in a real export: what it reaches at each scope, and what stays clean.
 *
 * A written read is of the clip gain on a flat clip, which is large enough to survive an encoder, and is held against
 * the signal worked over the clip's decoded source. The noise is a pixel wide, which no encoder keeps, so it is read
 * off the chain's own output through [RenderedFrames] against [DigicamSignal.pixelAt] over the same edit drawn with
 * no [Digicam]. How close the halo lands to the signal is read off the shader's own output in the filters module.
 */
class AndroidDigicamMediaTest {
  private val probe = Media3ExportProbe { filters() }
  private val rendered = RenderedFrames(probe.context)

  @Test
  fun aCompositionDigicamClipsThePictureOnTheSignal() =
    runTest(timeout = TIMEOUT) {
      val source = flatFile("digicam-mid.mp4", MID_GREY)
      val edit = Composition(listOf(Track(listOf(Clip(source.asMedia())))), listOf(QUIET))

      assertClipsOnTheSignal(edit, source)
    }

  @Test
  fun aClipDigicamClipsThePictureOnTheSignal() =
    runTest(timeout = TIMEOUT) {
      val source = flatFile("digicam-mid.mp4", MID_GREY)
      val edit = Composition(listOf(Track(listOf(Clip(source.asMedia(), effects = listOf(QUIET))))))

      assertClipsOnTheSignal(edit, source)
    }

  @Test
  fun aSmallerExportReadsTheSamePicture() =
    runTest(timeout = TIMEOUT) {
      val source = flatFile("digicam-mid.mp4", MID_GREY)
      val edit = Composition(listOf(Track(listOf(Clip(source.asMedia())))), listOf(QUIET))

      val small = assertClipsOnTheSignal(edit, source, ExportSpec(heightPx = SMALL_HEIGHT))
      val full = probe.written(edit, ExportSpec()).rgbAt(0.5f, 0.5f).first

      withClue("the ${SMALL_HEIGHT}p export read $small against $full at the source's height") {
        abs(small - full).toDouble() shouldBeLessThan WRITTEN_COLOUR_CODES
      }
    }

  @Test
  fun theChainDrawsTheNoiseOfEachCellCountedFromTheTop() =
    runTest(timeout = TIMEOUT) {
      val digicam = Digicam(sharpness = 0.5f, noise = 0.5f, clip = 0.5f)
      val params = DigicamParams.of(digicam)
      val tracks = listOf(Track(listOf(Clip(flatFile("digicam-dark.mp4", DARK).asMedia()))))

      val frame = rendered.frameAt(Composition(tracks, listOf(digicam)))
      val picture = rendered.frameAt(Composition(tracks))

      val width = frame.width
      val height = frame.height
      val tick = DigicamSignal.tickAt(0.0)
      val blur = DigicamSignal.blur(width, height) { x, y -> picture.channels(x, y) }
      var worst = 0.0
      var widest = 0.0
      for (y in 0 until height step READ_STEP) {
        for (x in 0 until width step READ_STEP) {
          val shown = picture.channels(x, y)
          val wanted = DigicamSignal.pixelAt(x, y, height, digicam.seed, tick, params, shown, blur.at(x, y))
          val quiet = DigicamSignal.pixelAt(x, y, height, digicam.seed, tick, QUIET_PARAMS, shown, blur.at(x, y))
          val read = frame.channels(x, y)
          for (channel in wanted.indices) {
            worst = max(worst, abs(read[channel] - wanted[channel]) * MAX_CODE)
            widest = max(widest, abs(wanted[channel] - quiet[channel]) * MAX_CODE)
          }
        }
      }

      withClue("the noise has to move a pixel well past the read's rounding for this to tell, $widest codes") {
        widest shouldBeGreaterThan renderCodes(digicam) * 4
      }
      withClue("the chain drew its worst pixel $worst codes off the signal") {
        worst shouldBeLessThan renderCodes(digicam)
      }
    }

  @Test
  fun aClipDigicamLeavesACanvasBarAsItsFill() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = flatFile("digicam-dark.mp4", DARK).asMedia()
      val edit = { effects: List<EffectSpec> ->
        Composition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = Fill.Solid(BAR_FILL))
      }
      val spec = ExportSpec()
      val shot = edit(listOf(crop, Digicam.Ccd))
      val bare = edit(listOf(crop))
      val plan = probe.plan(shot, spec)
      val written = probe.writtenBy(probe.finish(plan))
      val plain = probe.writtenBy(probe.finish(probe.plan(bare, spec)))
      val rect = placementOf(SOURCE, listOf(listOf(crop)), plan.output.size).drawnRect

      // Two pixels off the picture, clear of the picture's own edge and inside what a halo left in the bar would reach.
      val fx = rect.right + BAR_PIXELS / plan.output.size.width
      val drawn = rendered.frameAt(shot, spec).rgbAt(fx, 0.5f)
      val drawnPlain = rendered.frameAt(bare, spec).rgbAt(fx, 0.5f)
      withClue("the chain drew the bar $drawn against $drawnPlain beside the picture") {
        drawn.widestGapTo(drawnPlain).toDouble() shouldBeLessThan RENDER_CODES
      }
      val middle = (rect.right + 1f) / 2
      val read = written.rgbAt(middle, 0.5f)
      val wanted = plain.rgbAt(middle, 0.5f)
      withClue("the bar read $read against $wanted") {
        read.widestGapTo(wanted).toDouble() shouldBeLessThan BAR_CODES
      }
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest(timeout = TIMEOUT) {
      val source = flatFile("digicam-mid.mp4", MID_GREY).asMedia()
      val swatch = swatchOverlay()
      val shot = Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch, Digicam.Ccd))
      val plain = Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch))

      val read = redOfSwatch(shot)
      val wanted = redOfSwatch(plain)

      withClue("the clip gain has to move the swatch past what an encoder does for this to tell") {
        (
          DigicamSignal.clipped(
            wanted / MAX_CODE,
            DigicamParams.of(Digicam.Ccd),
          ) * MAX_CODE - wanted
        ) shouldBeGreaterThan
          WRITTEN_COLOUR_CODES * 2
      }
      withClue("the swatch read $read against $wanted") { abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  private suspend fun assertClipsOnTheSignal(
    edit: Composition,
    source: File,
    spec: ExportSpec = ExportSpec(),
  ): Int {
    val read = probe.written(edit, spec).rgbAt(0.5f, 0.5f).first
    val drawn = rendered.frameAt(edit, spec)
    val render = Color.red(drawn.getPixel(drawn.width / 2, drawn.height / 2))
    val frame = probe.frame(source, Duration.ZERO)
    val under = Color.red(frame.getPixel(frame.width / 2, frame.height / 2))
    val code = under / MAX_CODE
    val wanted = DigicamSignal.clipped(DigicamSignal.sharpened(code, code, QUIET_PARAMS), QUIET_PARAMS) * MAX_CODE

    withClue("the gain has to clear what an encoder moves for this to tell, $wanted codes over $under") {
      (wanted - under) shouldBeGreaterThan WRITTEN_COLOUR_CODES * 2
    }
    withClue("the picture must stay under white for this to read the middle of the range") {
      wanted shouldBeLessThan MAX_CODE * 0.9
    }
    withClue("the chain drew $render against $wanted before the encoder") {
      abs(render - wanted) shouldBeLessThan RENDER_CODES
    }
    withClue("the picture read $read against $wanted") {
      abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES
    }
    return read
  }

  // A read rounds to one eight bit code. The chain reads the decoder's picture finer than the eight bits the plain
  // frame comes back at, and the halo and the clip gain scale both that and the blur's own rounding up.
  private fun renderCodes(digicam: Digicam): Double {
    val params = DigicamParams.of(digicam)
    val blurred = ROUNDING_CODES * params.haloGain * params.clipGain
    val picture = ROUNDING_CODES * (1.0 + params.haloGain) * params.clipGain
    return ROUNDING_CODES + blurred + picture + SLACK_CODES
  }

  private fun Bitmap.channels(
    x: Int,
    y: Int,
  ): DoubleArray {
    val pixel = getPixel(x, y)
    return doubleArrayOf(Color.red(pixel) / MAX_CODE, Color.green(pixel) / MAX_CODE, Color.blue(pixel) / MAX_CODE)
  }

  private fun Triple<Int, Int, Int>.widestGapTo(other: Triple<Int, Int, Int>): Int =
    maxOf(abs(first - other.first), abs(second - other.second), abs(third - other.third))

  private suspend fun flatFile(
    name: String,
    code: Double,
  ): File {
    val file = File(probe.context.cacheDir, name)
    val grey = code.toFloat()
    if (!file.exists()) probe.writeBarsClip(file, SOURCE.width, SOURCE.height, listOf(floatArrayOf(grey, grey, grey)))
    return file
  }

  private fun File.asMedia(): MediaSource = MediaSource.of(path)

  private suspend fun redOfSwatch(edit: Composition): Double =
    probe
      .written(edit, ExportSpec())
      .rgbAt(SWATCH_FX, SWATCH_FY)
      .first
      .toDouble()

  private fun swatchOverlay(): ImageOverlay {
    val file = File(probe.context.cacheDir, "digicam-swatch.png")
    if (!file.exists()) {
      val bitmap = Bitmap.createBitmap(SWATCH_PX, SWATCH_PX, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(SWATCH_COLOR)
      file.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return ImageOverlay(ImageSource.of(file.path), Corner.TopStart, scale = SWATCH_SCALE)
  }

  private companion object {
    // The halo and the clip at the middle of their ranges with no noise, which an encoder would smear.
    val QUIET = Digicam(sharpness = 0.5f, noise = 0f, clip = 0.5f)
    val QUIET_PARAMS = DigicamParams.of(QUIET)
    val SOURCE = Size(640, 360)
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val MAX_CODE = 255.0
    const val BAR_FILL = 0xFF2F4F6F.toInt()
    const val SMALL_HEIGHT = 240
    const val READ_STEP = 3
    const val BAR_PIXELS = 2f

    // Under white after the clip gain at the middle of its range.
    const val MID_GREY = 0.5

    // Dark enough to take most of the noise.
    const val DARK = 0.2

    // Mid grey, which the clip gain would lift were the look to reach it.
    const val SWATCH_COLOR = 0xFF606060.toInt()
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f

    const val ROUNDING_CODES = 0.5
    const val SLACK_CODES = 0.25

    // The same bar written twice, with nothing drawn in it either time.
    const val BAR_CODES = 2.0
  }
}
