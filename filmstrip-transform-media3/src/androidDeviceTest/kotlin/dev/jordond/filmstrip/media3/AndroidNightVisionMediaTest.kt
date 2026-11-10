package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test

/**
 * What a [NightVision] leaves clean in a real export. How close each pixel lands is read off the shader's own output
 * in the filters module.
 */
class AndroidNightVisionMediaTest {
  private val probe = Media3ExportProbe { filters() }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()
      val swatch = swatchOverlay()

      val read = greenOfSwatch(Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch, NightVision())))
      val wanted = greenOfSwatch(Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch)))
      val under = greenOfSwatch(Composition(listOf(Track(listOf(Clip(source)))), listOf(NightVision())))
      val bare = greenOfSwatch(Composition(listOf(Track(listOf(Clip(source))))))

      withClue("the look has to move the picture under the swatch for this to tell, $under against $bare") {
        abs(under - bare) shouldBeGreaterThan ENCODER_CODES
      }
      withClue("the swatch read $read against $wanted") { abs(read - wanted) shouldBeLessThan ENCODER_CODES }
    }

  private suspend fun flatClip(): MediaSource {
    val file = File(probe.context.cacheDir, "nightvision-grey.mp4")
    val grey = GREY.toFloat()
    if (!file.exists()) probe.writeBarsClip(file, WIDTH, HEIGHT, listOf(floatArrayOf(grey, grey, grey)))
    return MediaSource.of(file.path)
  }

  private suspend fun greenOfSwatch(edit: Composition): Double =
    probe
      .written(edit, ExportSpec())
      .rgbAt(SWATCH_FX, SWATCH_FY)
      .second
      .toDouble()

  private fun swatchOverlay(): ImageOverlay {
    val file = File(probe.context.cacheDir, "nightvision-swatch.png")
    if (!file.exists()) {
      val bitmap = Bitmap.createBitmap(SWATCH_PX, SWATCH_PX, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(SWATCH_COLOR)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return ImageOverlay(ImageSource.of(file.path), Corner.TopStart, scale = SWATCH_SCALE)
  }

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val GREY = 0.5

    const val SWATCH_COLOR = 0xFFCCCCCC.toInt()
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f

    // What an encoder moves a flat read by.
    const val ENCODER_CODES = 5.0
  }
}
