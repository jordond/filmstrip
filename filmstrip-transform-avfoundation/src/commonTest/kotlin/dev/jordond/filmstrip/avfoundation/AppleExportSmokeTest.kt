package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One export through real AVFoundation and VideoToolbox to a real file, read back with the prober, with no pixel read.
 *
 * It runs in every test lane, the fast one included. The clip is a published fixture the test task fetches, so the run
 * needs no host ffmpeg.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleExportSmokeTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  // One clip, trimmed, scaled, written and read back at the size the plan promised.
  @Test
  fun `exports one clip trimmed and scaled`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture("apple_export_a.mp4")

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(500.milliseconds, 1_500.milliseconds) }
        }

      val output = NSTemporaryDirectory() + "filmstrip-apple-smoke-trimmed.mp4"
      val success = filmstrip.exportOrFail(composition, ExportSpec(heightPx = 240), output)

      success.info.video!!.displaySize shouldBe Size(426, 240)
      withClue("duration was ${success.info.duration}") {
        (success.info.duration in 800.milliseconds..1_300.milliseconds).shouldBeTrue()
      }
      withClue("the audio track did not survive") { success.info.audio.shouldNotBeNull() }

      NSFileManager.defaultManager.removeItemAtPath(output, error = null)
    }

  private companion object {
    val TIMEOUT = 2.seconds * 60
  }
}
