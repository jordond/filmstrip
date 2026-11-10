package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
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
 * What a [NightVision] leaves clean in a real Apple export. How close each pixel lands is read off the kernel's own
 * output in the filters module.
 */
class AppleNightVisionHardwareTest {
  private val filmstrip =
    Filmstrip {
      avFoundationBackend()
      filters()
    }

  @Test
  fun `an overlay at the same scope stays clean`() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()
      val swatch = swatch()
      val edit = { effects: List<EffectSpec> ->
        Composition(listOf(Track(listOf(Clip(source)))), effects)
      }

      val looked = greenOfSwatch(edit(listOf(swatch, NightVision())), "overlay-look")
      val plain = greenOfSwatch(edit(listOf(swatch)), "overlay-plain")
      val under = greenOfSwatch(edit(listOf(NightVision())), "under-look")
      val bare = greenOfSwatch(edit(emptyList()), "under-plain")

      withClue("the look has to move the picture under the swatch for this to tell, $under against $bare") {
        abs(under - bare) shouldBeGreaterThan WRITTEN_COLOUR_CODES
      }
      withClue("the swatch read $looked against $plain") { abs(looked - plain) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  private suspend fun greenOfSwatch(
    edit: Composition,
    name: String,
  ): Double {
    val path = export(edit, name)
    val read = frameOf(path).average(SWATCH_FX, SWATCH_FY).second.toDouble()
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
    val path = NSTemporaryDirectory() + "filmstrip-nightvision-flat.mp4"
    remove(path)
    val primaries = AVVideoColorPrimaries_ITU_R_709_2.orEmpty()
    val matrix = AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty()
    withContext(
      Dispatchers.Default,
    ) { writeTaggedBars(path, FRAMES, primaries, matrix, listOf(Triple(GREY, GREY, GREY))) }
    return MediaSource.of(path)
  }

  private suspend fun export(
    edit: Composition,
    name: String,
  ): String {
    val path = NSTemporaryDirectory() + "filmstrip-nightvision-$name.mp4"
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
    const val FRAMES = 15
    const val GREY = 128

    const val SWATCH_CODE = 204
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f
  }
}
