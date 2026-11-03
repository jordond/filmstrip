package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.chain.fillCIColor
import dev.jordond.filmstrip.avfoundation.internal.chain.hasColorSpace
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.test.AppleBufferCodes
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.HdrOverlayCells
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreVideo.CVBufferCopyAttachments
import platform.CoreVideo.CVBufferSetAttachment
import platform.CoreVideo.CVImageBufferCreateColorSpaceFromAttachments
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVAttachmentMode_ShouldPropagate
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_709_2
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import kotlin.math.abs
import kotlin.test.Test

/**
 * The color a fill is handed to Core Image as.
 *
 * [fillCIColor] falls back to sRGB when a platform cannot name an HDR color space, which draws a
 * bar at the wrong brightness rather than failing. That fallback is silent, so what these check is
 * that it is not being taken.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleFillTest {
  @Test
  fun `this platform can name both HDR color spaces`() {
    // The fallback in fillCIColor is only correct as a last resort. Reaching it on a platform the
    // library supports would mean every HDR fill quietly draws dim.
    withClue("PQ has no color space here, so a PQ fill falls back to sRGB") {
      HdrTransfer.Pq.hasColorSpace().shouldBeTrue()
    }
    withClue("HLG has no color space here, so an HLG fill falls back to sRGB") {
      HdrTransfer.Hlg.hasColorSpace().shouldBeTrue()
    }
  }

  @Test
  fun `an sdr fill keeps the components it was authored with`() {
    val color = fillCIColor(MAGENTA, transfer = null)

    color.red() shouldBeNear 1.0
    color.green() shouldBeNear 0.0
    color.blue() shouldBeNear 1.0
  }

  @Test
  fun `an sdr fill lands in a bt709 buffer as its own codes`() {
    AuthoredColors.COLORS.forEach { argb ->
      val landed = drawnIntoBt709Buffer(fillCIColor(argb, transfer = null))

      AuthoredColors.channelsOf(argb).forEachIndexed { channel, code ->
        withClue("a fill authored as ${AuthoredColors.channelsOf(argb)} landed as ${landed.toList()}") {
          (abs(landed[channel] - code) <= AuthoredColors.TOLERANCE).shouldBeTrue()
        }
      }
    }
  }

  /**
   * [color] drawn by Core Image over a small bi-planar video range buffer tagged BT.709, read back as the R'G'B'
   * codes its Y'CbCr planes hold.
   *
   * The buffer is written through the color space CoreVideo builds from its own tags, the way an SDR export's
   * composition output is.
   */
  private fun drawnIntoBt709Buffer(color: CIColor): FloatArray =
    memScoped {
      val out = alloc<CVPixelBufferRefVar>()
      val side = SIDE.toULong()
      val status = CVPixelBufferCreate(null, side, side, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, null, out.ptr)
      val buffer = withClue("no pixel buffer, CoreVideo returned $status") { out.value.shouldNotBeNull() }

      try {
        listOf(
          kCVImageBufferColorPrimariesKey to kCVImageBufferColorPrimaries_ITU_R_709_2,
          kCVImageBufferTransferFunctionKey to kCVImageBufferTransferFunction_ITU_R_709_2,
          kCVImageBufferYCbCrMatrixKey to kCVImageBufferYCbCrMatrix_ITU_R_709_2,
        ).forEach { (key, value) -> CVBufferSetAttachment(buffer, key, value, PROPAGATE) }
        val attachments = CVBufferCopyAttachments(buffer, PROPAGATE)
        val space = CVImageBufferCreateColorSpaceFromAttachments(attachments)
        attachments?.let(::CFRelease)

        try {
          val bounds = CGRectMake(0.0, 0.0, SIDE.toDouble(), SIDE.toDouble())
          val image = CIImage(color = color).imageByCroppingToRect(bounds)
          CIContext().render(image, toCVPixelBuffer = buffer, bounds = bounds, colorSpace = space)
        } finally {
          CGColorSpaceRelease(space)
        }
        AppleBufferCodes.videoRangeCodesIn(buffer, left = 0, top = 0, width = SIDE, height = SIDE)
      } finally {
        CVPixelBufferRelease(buffer)
      }
    }

  @Test
  fun `an hdr fill carries the signal the shared conversion gives rather than the authored fraction`() {
    val expected = HdrTransfer.Pq.signalFromNits(authoredHdrNits(HdrOverlayCells.WHITE))
    val color = fillCIColor(HdrOverlayCells.WHITE, HdrTransfer.Pq)

    color.red() shouldBeNear expected[0].toDouble()

    // One is what an untranslated white would carry, and it is the whole point of the conversion
    // that this is not that.
    withClue("an HDR white went in at ${color.red()}, which is the format's peak") {
      (color.red() < 0.9).shouldBeTrue()
    }
  }

  @Test
  fun `the two transfer functions hand Core Image different components`() {
    val pq = fillCIColor(HdrOverlayCells.WHITE, HdrTransfer.Pq).red()
    val hlg = fillCIColor(HdrOverlayCells.WHITE, HdrTransfer.Hlg).red()

    withClue("both transfer functions produced $pq, so one of them is not running") {
      (abs(pq - hlg) > 0.05).shouldBeTrue()
    }
  }

  @Test
  fun `black is black in every space`() {
    listOf(null, HdrTransfer.Pq, HdrTransfer.Hlg).forEach { transfer ->
      val color = fillCIColor(BLACK, transfer)

      color.red() shouldBeNear 0.0
      color.green() shouldBeNear 0.0
      color.blue() shouldBeNear 0.0
    }
  }

  private infix fun Double.shouldBeNear(expected: Double) {
    withClue("expected $expected but was $this") { (abs(this - expected) <= 1e-3).shouldBeTrue() }
  }

  private companion object {
    const val BLACK = 0xFF000000.toInt()
    const val MAGENTA = 0xFFFF00FF.toInt()
    const val SIDE = 16
    const val PROPAGATE = kCVAttachmentMode_ShouldPropagate
  }
}
