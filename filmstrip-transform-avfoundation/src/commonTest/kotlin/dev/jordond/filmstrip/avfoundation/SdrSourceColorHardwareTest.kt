package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.avfoundation.internal.composition.SdSourceColor
import dev.jordond.filmstrip.avfoundation.internal.composition.sdrHead
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_SMPTE_C
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_601_4
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalForeignApi::class)
class SdrSourceColorHardwareTest {
  @Test
  fun `an untagged clip draws beside hdr`() =
    runTest(timeout = TIMEOUT) {
      val engine = engine()
      listOf(SDR_FIXTURE, HD_SDR_FIXTURE).forEach { name ->
        engine.resolve(beside(fixture(name)), SPEC).shouldBeInstanceOf<ResolveResult.Resolved>()
      }
    }

  @Test
  fun `an sd clip draws beside hdr`() =
    runTest(timeout = TIMEOUT) {
      val smpteC = temporaryPath("smpte-c")
      val ebu = temporaryPath("ebu")
      withContext(Dispatchers.Default) {
        writeTaggedBars(smpteC, FRAMES, AVVideoColorPrimaries_SMPTE_C.orEmpty(), MATRIX_601)
        writeEbuBars(ebu, FRAMES)
      }
      val engine = engine()
      listOf(smpteC, ebu).forEach { path ->
        engine.resolve(beside(path), SPEC).shouldBeInstanceOf<ResolveResult.Resolved>()
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
      }
    }

  @Test
  fun `a still draws beside hdr`() =
    runTest(timeout = TIMEOUT) {
      val photo = ImageSource.of(photoFixture("filmstrip-sdr-limit-still", Size(640, 360), STILL_COLOR))
      val withStill =
        compositionOf {
          clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
          image(photo, CLIP)
        }
      engine().resolve(withStill, SPEC).shouldBeInstanceOf<ResolveResult.Resolved>()
    }

  @Test
  fun `an sd frame draws its colour from the shared matrices`() =
    runTest(timeout = TIMEOUT) {
      val path = temporaryPath("frame-sd")
      withContext(Dispatchers.Default) {
        writeTaggedBars(path, FRAMES, AVVideoColorPrimaries_SMPTE_C.orEmpty(), MATRIX_601)
      }
      val color = SdSourceColor(SMPTE_C_PRIMARIES, BT709, BT601)
      val drawn = firstFrameOf(path) { frame -> frame.sdrHead(color).firstBar() }
      val honest = codeFramesOf(path, 1).first().at(FIRST_BAR, MIDDLE)
      val shown = sdSourceDisplayed(doubleArrayOf(honest.r, honest.g, honest.b), SMPTE_C_PRIMARIES)
      assertCodes(drawn, Rgb(shown[0], shown[1], shown[2]), "the first bar", SD_FRAME_CODES)
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-sdr-limit-$name.mp4"

  // The middle of the first of the clip's eight bars, in the BT.709 codes it reads as.
  private fun CIImage.firstBar(): Rgb {
    val bar =
      extent.useContents { CGRectMake(origin.x, origin.y, size.width / BARS, size.height) }
    return imageByCroppingToRect(bar).pixelAtCentre(ColorSpace.Bt709.createCodeSpace())
  }

  private fun engine() =
    ComponentRegistry.Builder().build().let { components ->
      val prober = chainedProber(components)
      avFoundationExportEngine(components, prober, AvDriver(prober))
    }

  private fun beside(sdr: String): EditComposition =
    compositionOf {
      clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
      clip(MediaSource.of(sdr)) { trim(Duration.ZERO, CLIP) }
    }

  private companion object {
    const val SDR_FIXTURE = "apple_export_a.mp4"
    const val HD_SDR_FIXTURE = "apple_export_long.mp4"
    const val FRAMES = 3
    const val BARS = 8
    const val FIRST_BAR = 1f / BARS / 2
    const val MIDDLE = 0.5f
    const val BT709 = 1
    const val BT601 = 6

    // One 8-bit chroma step of the clip's H.264, measured at 1.1 codes against the same frame's honest read.
    const val SD_FRAME_CODES = 2.0
    val MATRIX_601 = AVVideoYCbCrMatrix_ITU_R_601_4.orEmpty()
    val STILL_COLOR = Triple(170, 120, 90)
    val CLIP = 100.milliseconds
    val TIMEOUT = 2.minutes
    val SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}
