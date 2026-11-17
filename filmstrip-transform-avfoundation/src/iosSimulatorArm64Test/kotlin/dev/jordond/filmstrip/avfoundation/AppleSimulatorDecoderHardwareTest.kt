package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The iOS simulator's own decoder answers. It has no ProRes decoder, so an export that draws a ProRes 4444 clip fails
 * there as `DecoderRejectedInput`, as measured on it.
 *
 * What a ProRes 4444 clip's alpha draws as is held on macOS and an iPhone by AppleSourceAlphaHardwareTest.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSimulatorDecoderHardwareTest {
  @Test
  fun `the simulator decodes no prores 4444 clip`() =
    runTest(timeout = TIMEOUT) {
      val composition =
        compositionOf {
          clip(MediaSource.Bytes(Base64.decode(AlphaClips.OPAQUE)))
          fill(Fill.Solid(SLATE))
        }
      val output = NSTemporaryDirectory() + "filmstrip-apple-simulator-prores.mp4"
      NSFileManager.defaultManager.removeItemAtPath(output, error = null)

      val finished =
        withContext(Dispatchers.Default) {
          Filmstrip { avFoundationBackend() }.export(composition, ExportSpec(), MediaSink.of(output)).toList().last()
        }
      NSFileManager.defaultManager.removeItemAtPath(output, error = null)

      withClue("the simulator drew a ProRes 4444 clip, so its decoder limit no longer holds") {
        finished
          .shouldBeInstanceOf<ExportStatus.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.DecoderRejectedInput>()
      }
    }

  private companion object {
    val TIMEOUT = 2.minutes
    const val SLATE = 0xFF2F4F6F.toInt()
  }
}
