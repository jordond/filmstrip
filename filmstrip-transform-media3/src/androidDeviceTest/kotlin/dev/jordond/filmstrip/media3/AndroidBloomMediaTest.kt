package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Color
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.bloom.Bloom
import dev.jordond.filmstrip.effects.filters.bloom.BloomParams
import dev.jordond.filmstrip.effects.filters.bloom.BloomSignal
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
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Where a [Bloom] lands in a real export: what it reaches at each scope, and what stays clean.
 *
 * Every read is held against the signal worked over what the picture reads with no [Bloom]. A flat clip is read off
 * its decoded source, so only the bloom's own encode moves it, and off the chain's own output through
 * [RenderedFrames], which holds it to [RENDER_CODES] before the encoder and to [WRITTEN_COLOUR_CODES] after. How close
 * each pixel lands to the signal is read off the shader's own output in the filters module.
 */
class AndroidBloomMediaTest {
  private val probe = Media3ExportProbe { filters() }
  private val rendered = RenderedFrames(probe.context)

  @Test
  fun aCompositionBloomGlowsThePictureOnTheSignal() =
    runTest(timeout = TIMEOUT) {
      val source = flatFile("bloom-knee.mp4", ON_THE_KNEE)
      val edit = Composition(listOf(Track(listOf(Clip(source.asMedia())))), listOf(BLOOM))

      assertGlowsOnTheSignal(edit, source)
    }

  @Test
  fun aClipBloomGlowsThePictureOnTheSignal() =
    runTest(timeout = TIMEOUT) {
      val source = flatFile("bloom-knee.mp4", ON_THE_KNEE)
      val edit = Composition(listOf(Track(listOf(Clip(source.asMedia(), effects = listOf(BLOOM))))))

      assertGlowsOnTheSignal(edit, source)
    }

  @Test
  fun aSmallerExportReadsTheSameGlow() =
    runTest(timeout = TIMEOUT) {
      val source = flatFile("bloom-knee.mp4", ON_THE_KNEE)
      val edit = Composition(listOf(Track(listOf(Clip(source.asMedia())))), listOf(BLOOM))

      val small = assertGlowsOnTheSignal(edit, source, ExportSpec(heightPx = SMALL_HEIGHT))
      val full = probe.written(edit, ExportSpec()).rgbAt(0.5f, 0.5f).first

      withClue("the ${SMALL_HEIGHT}p export read $small against $full at the source's height") {
        abs(small - full).toDouble() shouldBeLessThan WRITTEN_COLOUR_CODES
      }
    }

  @Test
  fun aCompositionBloomGlowsFromThePictureIntoABar() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = flatFile("bloom-bright.mp4", BRIGHT).asMedia()
      val edit = { effects: List<EffectSpec> ->
        Composition(listOf(Track(listOf(Clip(source)))), effects, fill = Fill.Solid(BLACK))
      }

      val spec = ExportSpec()
      val plan = probe.plan(edit(listOf(crop, BLOOM)), spec)
      val written = probe.writtenBy(probe.finish(plan))
      val plain = probe.writtenBy(probe.finish(probe.plan(edit(listOf(crop)), spec)))

      val size = plan.output.size
      val rect = placementOf(SOURCE, listOf(listOf(crop)), size).drawnRect
      val params = BloomParams.of(BLOOM)
      val glow =
        BloomSignal.glow(size.width, size.height, params) { x, _ ->
          val inside = (x + 0.5) / size.width in rect.left..rect.right
          if (inside) doubleArrayOf(BRIGHT, BRIGHT, BRIGHT) else doubleArrayOf(0.0, 0.0, 0.0)
        }
      val blur = BloomSignal.blurPlan(BLOOM.spread.toDouble(), size.width)
      val fx = rect.right + (blur.sigmaPx * blur.factor / size.width).toFloat()
      val x = (fx * size.width).toInt()
      val y = size.height / 2
      val wanted = BloomSignal.bloomed(0.0, 0.0, 0.0, glow.at(x, y), params)[0] * MAX_CODE
      val lift = (written.rgbAt(fx, 0.5f).first - plain.rgbAt(fx, 0.5f).first).toDouble()

      withClue("the glow has to clear what an encoder moves for this to tell, $wanted codes") {
        wanted shouldBeGreaterThan WRITTEN_COLOUR_CODES * 2
      }
      withClue("one sigma into the bar lifted $lift against $wanted") {
        abs(lift - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES
      }
    }

  @Test
  fun aClipBloomLeavesACanvasBarAsItsFill() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = flatFile("bloom-bright.mp4", BRIGHT).asMedia()
      val edit = { effects: List<EffectSpec> ->
        Composition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = Fill.Solid(BAR_FILL))
      }
      val spec = ExportSpec()
      val bloomed = edit(listOf(crop, Bloom(intensity = 1f, threshold = 0.5f, spread = 1f)))
      val bare = edit(listOf(crop))
      val plan = probe.plan(bloomed, spec)
      val written = probe.writtenBy(probe.finish(plan))
      val plain = probe.writtenBy(probe.finish(probe.plan(bare, spec)))
      val rect = placementOf(SOURCE, listOf(listOf(crop)), plan.output.size).drawnRect

      // One sigma off the picture, where a glow left in the bar is at its widest clear of the picture's own edge.
      val blur = BloomSignal.blurPlan(1.0, plan.output.size.width)
      val fx = rect.right + (blur.sigmaPx * blur.factor / plan.output.size.width).toFloat()
      val drawn = rendered.frameAt(bloomed, spec).rgbAt(fx, 0.5f)
      val drawnPlain = rendered.frameAt(bare, spec).rgbAt(fx, 0.5f)
      withClue("the chain drew the bar $drawn against $drawnPlain a sigma off the picture") {
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
      val source = flatFile("bloom-knee.mp4", ON_THE_KNEE).asMedia()
      val swatch = swatchOverlay()
      val bloomed = Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch, BLOOM))
      val plain = Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch))

      val read = redOfSwatch(bloomed)
      val wanted = redOfSwatch(plain)

      withClue("the swatch read $read against $wanted") { abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  private suspend fun assertGlowsOnTheSignal(
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
    val params = BloomParams.of(BLOOM)
    val response = BloomSignal.response(code, code, code, params.threshold)
    val wanted = BloomSignal.bloomed(code, code, code, response, params)[0] * MAX_CODE

    withClue("the glow has to clear what an encoder moves for this to tell, $wanted codes over $under") {
      (wanted - under) shouldBeGreaterThan WRITTEN_COLOUR_CODES * 2
    }
    withClue("the chain drew $render against $wanted before the encoder") {
      abs(render - wanted) shouldBeLessThan RENDER_CODES
    }
    withClue("the picture read $read against $wanted") {
      abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES
    }
    return read
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
    val file = File(probe.context.cacheDir, "bloom-swatch.png")
    if (!file.exists()) {
      val bitmap = Bitmap.createBitmap(SWATCH_PX, SWATCH_PX, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(SWATCH_COLOR)
      file.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return ImageOverlay(ImageSource.of(file.path), Corner.TopStart, scale = SWATCH_SCALE)
  }

  private companion object {
    val BLOOM = Bloom(intensity = 0.75f)
    val SOURCE = Size(640, 360)
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val MAX_CODE = 255.0
    const val BLACK = 0xFF000000.toInt()
    const val BAR_FILL = 0xFF2F4F6F.toInt()
    const val SMALL_HEIGHT = 240

    // Sits part way up the default knee, so the response neither vanishes nor passes the whole colour.
    const val ON_THE_KNEE = 0.8

    // Past the default knee's end, so the picture gives the blur its whole colour.
    const val BRIGHT = 0.95

    // Bright enough to glow were the bloom to reach it.
    const val SWATCH_COLOR = 0xFFCCCCCC.toInt()
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f

    // The same bar written twice, with nothing drawn in it either time.
    const val BAR_CODES = 2.0
  }
}
