package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.composition.sdrTransferLimit
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_P3_D65
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalForeignApi::class)
class SdrSourceColorTest {
  @Test
  fun `an os without the pixel transfer names the limit`() {
    sdrTransferLimit(available = false).shouldNotBeNull() shouldContain "iOS 16"
  }

  @Test
  fun `an os with the pixel transfer has no limit`() {
    sdrTransferLimit(available = true).shouldBeNull()
  }

  @Test
  fun `an os without the pixel transfer refuses an untagged clip beside hdr by name at plan time`() =
    runTest(timeout = TIMEOUT) {
      engine(pixelTransferExists = false)
        .resolve(beside(fixture(SDR_FIXTURE)), SPEC)
        .shouldBeInstanceOf<ResolveResult.Refused>()
        .error.message shouldBe sdrTransferLimit(available = false)
    }

  @Test
  fun `an os without the pixel transfer refuses a clip of other primaries beside hdr`() =
    runTest(timeout = TIMEOUT) {
      val p3 = NSTemporaryDirectory() + "filmstrip-sdr-limit-p3.mp4"
      withContext(Dispatchers.Default) {
        writeTaggedBars(p3, FRAMES, AVVideoColorPrimaries_P3_D65.orEmpty(), MATRIX_709)
      }
      engine(pixelTransferExists = false)
        .resolve(beside(p3), SPEC)
        .shouldBeInstanceOf<ResolveResult.Refused>()
        .error.message shouldBe sdrTransferLimit(available = false)
      NSFileManager.defaultManager.removeItemAtPath(p3, error = null)
    }

  @Test
  fun `an os without the pixel transfer still draws a bt709 clip and hdr on its own`() =
    runTest(timeout = TIMEOUT) {
      val written = NSTemporaryDirectory() + "filmstrip-sdr-limit-written.mp4"
      NSFileManager.defaultManager.removeItemAtPath(written, error = null)
      withContext(Dispatchers.Default) {
        Filmstrip { avFoundationBackend() }
          .export(reencoded(fixture(SDR_FIXTURE)), SPEC, MediaSink.of(written))
          .toList()
          .last()
          .shouldBeInstanceOf<ExportStatus.Success>()
      }
      val engine = engine(pixelTransferExists = false)
      engine.resolve(beside(written), SPEC).shouldBeInstanceOf<ResolveResult.Resolved>()
      engine.resolve(alone(hdrFixture(HdrTransfer.Hlg)), SPEC).shouldBeInstanceOf<ResolveResult.Resolved>()
      NSFileManager.defaultManager.removeItemAtPath(written, error = null)
    }

  @Test
  fun `an audio track of an untagged clip is not drawn so it is not refused`() =
    runTest(timeout = TIMEOUT) {
      val withBed =
        compositionOf {
          clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
          track(TrackContent.Audio) {
            clip(MediaSource.of(fixture(SDR_FIXTURE))) { trim(Duration.ZERO, CLIP) }
          }
        }
      engine(pixelTransferExists = false).resolve(withBed, SPEC).shouldBeInstanceOf<ResolveResult.Resolved>()
    }

  @Test
  fun `an os with the pixel transfer draws an untagged clip beside hdr`() =
    runTest(timeout = TIMEOUT) {
      engine(pixelTransferExists = true)
        .resolve(beside(fixture(SDR_FIXTURE)), SPEC)
        .shouldBeInstanceOf<ResolveResult.Resolved>()
    }

  private fun engine(pixelTransferExists: Boolean) =
    ComponentRegistry.Builder().build().let { components ->
      val prober = chainedProber(components)
      avFoundationExportEngine(components, prober, AvDriver(prober, pixelTransferExists = pixelTransferExists))
    }

  private fun beside(sdr: String): EditComposition =
    compositionOf {
      clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
      clip(MediaSource.of(sdr)) { trim(Duration.ZERO, CLIP) }
    }

  private fun alone(path: String): EditComposition =
    compositionOf { clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) } }

  // An effect forces the encode, since a clip already inside the height limit would copy across with the tags it has.
  private fun reencoded(path: String): EditComposition =
    compositionOf {
      clip(MediaSource.of(path)) {
        trim(Duration.ZERO, CLIP)
        addEffects(listOf(Invert()))
      }
    }

  private companion object {
    const val SDR_FIXTURE = "apple_export_a.mp4"
    const val FRAMES = 3
    val MATRIX_709 = AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty()
    val CLIP = 100.milliseconds
    val TIMEOUT = 2.minutes
    val SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}
