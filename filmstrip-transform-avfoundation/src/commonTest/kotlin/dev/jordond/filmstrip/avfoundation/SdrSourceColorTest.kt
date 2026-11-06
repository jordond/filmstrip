package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.composition.SdSourceColor
import dev.jordond.filmstrip.avfoundation.internal.composition.sdrHead
import dev.jordond.filmstrip.avfoundation.internal.composition.sdrTransferLimit
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.AppleScratch
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoColorPrimaries_P3_D65
import platform.AVFoundation.AVVideoColorPrimaries_SMPTE_C
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_601_4
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
  fun `an os without the pixel transfer draws an untagged clip beside hdr`() =
    runTest(timeout = TIMEOUT) {
      val engine = engine(pixelTransferExists = false)
      listOf(SDR_FIXTURE, HD_SDR_FIXTURE).forEach { name ->
        engine.resolve(beside(fixture(name)), SPEC).shouldBeInstanceOf<ResolveResult.Resolved>()
      }
    }

  @Test
  fun `an os without the pixel transfer draws an sd clip beside hdr`() =
    runTest(timeout = TIMEOUT) {
      val smpteC = temporaryPath("smpte-c")
      val ebu = temporaryPath("ebu")
      withContext(Dispatchers.Default) {
        writeTaggedBars(smpteC, FRAMES, AVVideoColorPrimaries_SMPTE_C.orEmpty(), MATRIX_601)
        writeEbuBars(ebu, FRAMES)
      }
      val engine = engine(pixelTransferExists = false)
      listOf(smpteC, ebu).forEach { path ->
        engine.resolve(beside(path), SPEC).shouldBeInstanceOf<ResolveResult.Resolved>()
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
      }
    }

  @Test
  fun `an os without the pixel transfer draws a still beside hdr`() =
    runTest(timeout = TIMEOUT) {
      val photo = ImageSource.of(photoFixture("filmstrip-sdr-limit-still", Size(640, 360), STILL_COLOR))
      val withStill =
        compositionOf {
          clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
          image(photo, CLIP)
        }
      engine(pixelTransferExists = false).resolve(withStill, SPEC).shouldBeInstanceOf<ResolveResult.Resolved>()
    }

  @Test
  fun `an sd frame draws its colour with no pixel transfer`() =
    runTest(timeout = TIMEOUT) {
      val path = temporaryPath("frame-sd")
      withContext(Dispatchers.Default) {
        writeTaggedBars(path, FRAMES, AVVideoColorPrimaries_SMPTE_C.orEmpty(), MATRIX_601)
      }
      val color = SdSourceColor(SMPTE_C_PRIMARIES, BT709, BT601)
      val drawn = firstFrameOf(path) { frame -> frame.sdrHead(color, transferExists = false).firstBar() }
      val honest = codeFramesOf(path, 1).first().at(FIRST_BAR, MIDDLE)
      val shown = sdSourceDisplayed(doubleArrayOf(honest.r, honest.g, honest.b), SMPTE_C_PRIMARIES)
      assertCodes(drawn, Rgb(shown[0], shown[1], shown[2]), "the first bar", SD_FRAME_CODES)
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

  @Test
  fun `a frame of other primaries names the limit with no pixel transfer`() =
    runTest(timeout = TIMEOUT) {
      val path = temporaryPath("frame-p3")
      withContext(Dispatchers.Default) {
        writeTaggedBars(path, FRAMES, AVVideoColorPrimaries_P3_D65.orEmpty(), MATRIX_709)
      }
      firstFrameOf(path) { frame ->
        assertFailsWith<IllegalStateException> { frame.sdrHead(null, transferExists = false) }.message shouldBe
          sdrTransferLimit(available = false)
      }
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

  @Test
  fun `an os without the pixel transfer refuses a clip of other primaries beside hdr`() =
    runTest(timeout = TIMEOUT) {
      val p3 = temporaryPath("p3")
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
      val written = temporaryPath("written")
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

  @Test
  fun `an os without the pixel transfer refuses a buffer it cannot write as not bt709`() =
    runTest(timeout = TIMEOUT) {
      val blocked = uniqueRoot()
      NSFileManager.defaultManager.createFileAtPath(blocked, null, null) shouldBe true
      val tagged = taggedBt709Buffer()
      try {
        val driver = AvDriver(UNUSED_PROBER, pixelTransferExists = false, bytesRoot = blocked)
        driver.toneMapLimit(listOf(tagged)) shouldBe sdrTransferLimit(available = false)

        engine(pixelTransferExists = false, bytesRoot = blocked)
          .resolve(bufferBeside(tagged), SPEC)
          .shouldBeInstanceOf<ResolveResult.Refused>()
          .error.message shouldBe sdrTransferLimit(available = false)
      } finally {
        NSFileManager.defaultManager.removeItemAtPath(blocked, error = null)
      }
    }

  @Test
  fun `an os without the pixel transfer opens a buffer under the root it was given`() =
    runTest(timeout = TIMEOUT) {
      val root = uniqueRoot()
      val tagged = taggedBt709Buffer()
      try {
        val driver = AvDriver(UNUSED_PROBER, pixelTransferExists = false, bytesRoot = root)

        driver.toneMapLimit(listOf(tagged)).shouldBeNull()
        NSFileManager.defaultManager.fileExistsAtPath("$root/filmstrip-media-bytes") shouldBe true
      } finally {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
      }
    }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-sdr-limit-$name.mp4"

  // The middle of the first of the clip's eight bars, in the BT.709 codes it reads as.
  private fun CIImage.firstBar(): Rgb {
    val bar =
      extent.useContents { CGRectMake(origin.x, origin.y, size.width / BARS, size.height) }
    return imageByCroppingToRect(bar).pixelAtCentre(ColorSpace.Bt709.createCodeSpace())
  }

  private suspend fun taggedBt709Buffer(): MediaSource.Bytes {
    val path = uniqueRoot() + ".mp4"
    withContext(Dispatchers.Default) {
      writeTaggedBars(path, FRAMES, AVVideoColorPrimaries_ITU_R_709_2.orEmpty(), MATRIX_709)
    }
    return MediaSource.Bytes(readBytes(path)).also { NSFileManager.defaultManager.removeItemAtPath(path, error = null) }
  }

  private fun uniqueRoot(): String =
    NSTemporaryDirectory() + "filmstrip-sdr-limit-" + NSProcessInfo.processInfo.globallyUniqueString

  private fun bufferBeside(buffer: MediaSource.Bytes): EditComposition =
    compositionOf {
      clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
      clip(buffer) { trim(Duration.ZERO, CLIP) }
    }

  private fun engine(
    pixelTransferExists: Boolean,
    bytesRoot: String = AppleScratch.defaultRoot,
  ) = ComponentRegistry.Builder().build().let { components ->
    val prober = chainedProber(components)
    avFoundationExportEngine(
      components,
      prober,
      AvDriver(prober, pixelTransferExists = pixelTransferExists, bytesRoot = bytesRoot),
    )
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
    const val HD_SDR_FIXTURE = "apple_export_long.mp4"
    const val FRAMES = 3
    val UNUSED_PROBER = MediaProber { error("No probe is expected here.") }
    const val BARS = 8
    const val FIRST_BAR = 1f / BARS / 2
    const val MIDDLE = 0.5f
    const val BT709 = 1
    const val BT601 = 6

    // One 8-bit chroma step of the clip's H.264, measured at 1.1 codes against the same frame's honest read.
    const val SD_FRAME_CODES = 2.0
    val MATRIX_709 = AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty()
    val MATRIX_601 = AVVideoYCbCrMatrix_ITU_R_601_4.orEmpty()
    val STILL_COLOR = Triple(170, 120, 90)
    val CLIP = 100.milliseconds
    val TIMEOUT = 2.minutes
    val SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}
