package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.CrtParams
import dev.jordond.filmstrip.effects.filters.crt.CrtSignal
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Where a [Crt] lands in a real Apple export: what it reaches at each scope, and what stays clean.
 *
 * Reads here are of what survives an encoder, the frame's falloff and the black past the curve, held against the signal
 * worked over the same read with no [Crt]. How close each pixel lands, the patterns included, is read off the kernel's
 * own output in the filters module.
 */
class AppleCrtHardwareTest {
  private val filmstrip =
    Filmstrip {
      avFoundationBackend()
      filters()
    }

  @Test
  fun `a composition crt draws the falloff on the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()

      assertOnTheSignal(source, EditComposition(listOf(Track(listOf(Clip(source)))), listOf(Crt())))
    }

  @Test
  fun `a clip crt draws the falloff on the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()

      assertOnTheSignal(source, EditComposition(listOf(Track(listOf(Clip(source, effects = listOf(Crt())))))))
    }

  @Test
  fun `a composition crt reaches the bars`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = flatClip()
      val edit = { effects: List<EffectSpec> ->
        EditComposition(listOf(Track(listOf(Clip(source)))), effects, fill = Fill.Solid(BAR_FILL))
      }

      val plain = export(edit(listOf(crop)), "bar-plain")
      val looked = export(edit(listOf(crop, Crt())), "bar-crt")

      val read = frameOf(looked).average(BAR_FX, CENTRE).third.toDouble()
      val wanted = frameOf(plain).average(BAR_FX, CENTRE).third.toDouble()
      withClue("the bar read $read against $wanted") { abs(read - wanted) shouldBeGreaterThan WRITTEN_COLOUR_CODES }
      remove(plain)
      remove(looked)
    }

  @Test
  fun `a clip crt leaves a canvas bar as its fill`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = flatClip()
      val edit = { effects: List<EffectSpec> ->
        EditComposition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = Fill.Solid(BAR_FILL))
      }

      val plain = export(edit(listOf(crop)), "canvas-plain")
      val looked = export(edit(listOf(crop, Crt())), "canvas-crt")

      val read = frameOf(looked).average(BAR_FX, CENTRE).first.toDouble()
      val wanted = frameOf(plain).average(BAR_FX, CENTRE).first.toDouble()
      withClue("the bar read $read against $wanted") { abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
      remove(plain)
      remove(looked)
    }

  @Test
  fun `an overlay at the same scope stays clean`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()
      val swatch = swatch()

      val plain = redOfSwatch(EditComposition(listOf(Track(listOf(Clip(source)))), listOf(swatch)), "overlay-plain")
      val looked =
        redOfSwatch(EditComposition(listOf(Track(listOf(Clip(source)))), listOf(swatch, Crt())), "overlay-crt")

      withClue("the swatch read $looked against $plain") { abs(looked - plain) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  @Test
  fun `a clip overlay under a composition crt is burned`() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip(), effects = listOf(swatch()))

      val plain = redOfSwatch(EditComposition(listOf(Track(listOf(clip)))), "burned-plain")
      val looked = redOfSwatch(EditComposition(listOf(Track(listOf(clip))), listOf(Crt())), "burned-crt")

      withClue(
        "the swatch read $looked against $plain",
      ) { abs(looked - plain) shouldBeGreaterThan WRITTEN_COLOUR_CODES }
    }

  // A read of the pattern's mean near the top of the frame's middle, where the falloff has moved with no curve yet.
  private suspend fun assertOnTheSignal(
    source: MediaSource,
    edit: EditComposition,
  ) {
    val looked = export(edit, "signal-crt")
    val plain = export(EditComposition(listOf(Track(listOf(Clip(source))))), "signal-plain")
    val params = CrtParams.of(Crt())
    val read = codesOf(looked).average(CENTRE, READ_FY).second.toDouble()
    val under = codesOf(plain).average(CENTRE, READ_FY).second.toDouble()
    val wanted =
      under * params.gain * (1.0 - params.scanlineDepth / 2.0) * (1.0 - params.maskDepth / 2.0) *
        CrtSignal.vignetteAt(CENTRE.toDouble(), READ_FY.toDouble(), SOURCE_WIDTH, SOURCE_HEIGHT, params.vignette)

    withClue("the look has to clear what an encoder moves for this to tell, $wanted codes over $under") {
      abs(wanted - under) shouldBeGreaterThan WRITTEN_COLOUR_CODES
    }
    withClue("the read $read against $wanted") { abs(read - wanted) shouldBeLessThan SIGNAL_CODES }
    remove(looked)
    remove(plain)
  }

  private suspend fun redOfSwatch(
    edit: EditComposition,
    name: String,
  ): Double {
    val path = export(edit, name)
    val read = frameOf(path).average(SWATCH_FX, SWATCH_FY).first.toDouble()
    remove(path)
    return read
  }

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(solidPng(SWATCH_PX, SWATCH_PX, Triple(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE))),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  private suspend fun flatClip(): MediaSource {
    val path = NSTemporaryDirectory() + "filmstrip-crt-flat.mp4"
    remove(path)
    val primaries = AVVideoColorPrimaries_ITU_R_709_2.orEmpty()
    val matrix = AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty()
    withContext(
      Dispatchers.Default,
    ) { writeTaggedBars(path, FRAMES, primaries, matrix, listOf(Triple(BRIGHT, BRIGHT, BRIGHT))) }
    return MediaSource.of(path)
  }

  private suspend fun export(
    edit: EditComposition,
    name: String,
  ): String {
    val path = NSTemporaryDirectory() + "filmstrip-crt-$name.mp4"
    remove(path)
    val status =
      withContext(Dispatchers.Default) { filmstrip.export(edit, ExportSpec(), MediaSink.of(path)).toList().last() }
    status.shouldBeInstanceOf<ExportStatus.Success>()
    return path
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
  }

  private companion object {
    val TIMEOUT = 3.minutes
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val FRAMES = 15
    const val CENTRE = 0.5f
    const val READ_FY = 0.15f
    const val BAR_FX = 0.1f
    const val BAR_FILL = 0xFF2F4F6F.toInt()
    const val BRIGHT = 180

    // writeTaggedBars' frame, which the falloff is worked over.
    const val SOURCE_WIDTH = 640
    const val SOURCE_HEIGHT = 360

    const val SWATCH_CODE = 204
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f

    // An encoder over a pattern it softens, on a read of the pattern's mean.
    const val SIGNAL_CODES = 8.0
  }
}
