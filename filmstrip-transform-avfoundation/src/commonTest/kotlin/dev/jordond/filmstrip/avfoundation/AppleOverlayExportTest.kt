package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.overlay.imageOverlay
import dev.jordond.filmstrip.effects.overlay.textOverlay
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The composite half of the pipeline, exported for real.
 *
 * The placement arithmetic is asserted in `filmstrip-effects`, where it is pure. What these cover
 * is that a composited chain survives a whole export. The frames render, the file is written, and
 * the plan does not quietly refuse the effect on the way.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleOverlayExportTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `burns a watermark into every corner`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      Corner.entries.forEach { corner ->
        val composition =
          compositionOf {
            clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
            effects { imageOverlay(ImageSource.ofBytes(RED_PNG), corner, margin = 0.04f, scale = 0.2f) }
          }

        val output = temporaryPath("watermark-$corner")
        val success = exported(composition, ExportSpec(targetHeight = 180), output)
        success.info.video!!.displaySize shouldBe Size(320, 180)
        remove(output)
      }
    }

  @Test
  fun `burns text into the frame`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
          effects { textOverlay("filmstrip", TextStyle(fontSize = 0.12f, backgroundColor = BLACK)) }
        }

      val output = temporaryPath("text")
      val success = exported(composition, ExportSpec(targetHeight = 180), output)

      success.info.video!!.displaySize shouldBe Size(320, 180)
      remove(output)
    }

  @Test
  fun `dims the whole frame`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
          effects { brightness(0.5f) }
        }

      val output = temporaryPath("brightness")
      exported(composition, ExportSpec(targetHeight = 180), output)
      remove(output)
    }

  // Whether the overlay is drawn on a given frame is asserted step by step in filmstrip-effects,
  // where it is pure. This is the check that a timed overlay reaches the encoder at all instead of
  // being refused while planning.
  @Test
  fun `exports a watermark that appears for part of the composition`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape))
          effects {
            imageOverlay(
              ImageSource.ofBytes(RED_PNG),
              Corner.TopEnd,
              visibleDuring = TimeRange(500.milliseconds, 1_500.milliseconds),
            )
          }
        }

      val verdict = withContext(Dispatchers.Default) { filmstrip.plan(composition, ExportSpec(targetHeight = 180)) }
      assertIs<Verdict.Capable>(verdict)

      val output = temporaryPath("timed-watermark")
      exported(composition, ExportSpec(targetHeight = 180), output)
      remove(output)
    }

  @Test
  fun `refuses a watermark whose image cannot be read`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape))
          effects { imageOverlay(ImageSource.of("/nonexistent/badge.png"), Corner.TopStart) }
        }

      val verdict = withContext(Dispatchers.Default) { filmstrip.plan(composition, ExportSpec(targetHeight = 180)) }
      val incapable = assertIs<Verdict.Incapable>(verdict)
      assertIs<ExportError.UnsupportedEffect>(incapable.reasons.single()).specId shouldBe EffectIds.IMAGE_OVERLAY
    }

  private suspend fun exported(
    composition: EditComposition,
    spec: ExportSpec,
    output: String,
  ): ExportStatus.Success =
    withContext(Dispatchers.Default) {
      val statuses = filmstrip.export(composition, spec, MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> assertIs<ExportStatus.Success>(finished)
      }
    }

  private fun fixture(): String? {
    val directory = fixtures ?: return null
    val path = "$directory/apple_export_a.mp4"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val BLACK = 0xFF000000.toInt()
    val TIMEOUT = 2.seconds * 60

    // A four by two opaque red PNG. Not square, so a watermark's height following the image's own
    // aspect is exercised.
    val RED_PNG =
      (
        "89504e470d0a1a0a0000000d4948445200000004000000020806000000" +
          "7fa87d630000001249444154789c63f8cfc0f01f1933a00b00000f210ff1" +
          "0437c69f0000000049454e44ae426082"
      ).decodeHex()

    fun String.decodeHex(): ByteArray =
      ByteArray(length / 2) { index ->
        ((this[index * 2].digitToInt(HEX) shl 4) or this[index * 2 + 1].digitToInt(HEX)).toByte()
      }

    const val HEX = 16
  }
}
