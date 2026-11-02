package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.test.AppleStillSamples.EIGHT_BIT_STEP
import dev.jordond.filmstrip.test.AppleStillSamples.SAMPLE_MASK
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGImageGetBitmapInfo
import platform.CoreGraphics.CGImageGetBitsPerComponent
import platform.CoreGraphics.CGImageGetBitsPerPixel
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapAlphaInfoMask
import platform.CoreGraphics.kCGBitmapByteOrder16Little
import platform.CoreGraphics.kCGBitmapByteOrderMask
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Holds every twelve-bit AVIF and HEIC under each matrix [AppleStillSamples.twelveBit] covers, at both ranges, restated
 * the way [AppleStillSamples.landing] lands it, to the sixteen-bit bitmap ImageIO decodes it to, or to ImageIO handing
 * out none of its samples. Also holds the twelve-bit model to failing where its decode is unmeasured.
 */
@OptIn(InternalFilmstripApi::class)
class AppleTwelveBitStillsTest {
  @Test
  fun aTwelveBitDecodeOnAnIphoneIsUnmeasured() {
    val read = shortArrayOf(9, 10, 11, 12)

    val failure =
      assertFailsWith<AppleStillSamples.UnmeasuredDecode> {
        AppleStillSamples.twelveBit(CicpTag(1, 13, 1, true), heic = false, read, ApplePlatform.Iphone)
      }

    assertContains(failure.message.orEmpty(), "iOS hardware")
    assertContains(failure.message.orEmpty(), "${read.toList()}")
  }

  @Test
  fun aTwelveBitHeicAtLimitedRangeHandsOutNoSamples() {
    listOf(ApplePlatform.MacOs, ApplePlatform.Simulator).forEach { platform ->
      AppleStillSamples.TWELVE_BIT_MATRICES.forEach { matrix ->
        val nclx = CicpTag(1, 13, matrix, false)
        val decodes = AppleStillSamples.twelveBit(nclx, heic = true, read = null, platform)
        assertTrue(decodes.isEmpty(), "the limited range HEIC under matrix $matrix decodes on ${platform.label}")
      }
    }
  }

  // A mid code of SMPTE 240M and BT.2020 constant luminance is read apart from the matrix the nclx names, so a model
  // reading that matrix, or BT.709 for both, fails here. FCC's mid code rounds to BT.601's, so it is left out.
  @Test
  fun aTwelveBitStillUnderAnUnreadMatrixLandsThroughTheNearestReadableMatrix() {
    val (y, cb, cr) = AvifColourPictures.TWELVE_BIT_YCBCR
    for (platform in listOf(ApplePlatform.MacOs, ApplePlatform.Simulator)) {
      for (matrix in listOf(7, 10)) {
        val nclx = CicpTag(1, 13, matrix, true)
        val landed = AppleStillSamples.landing(nclx, unsubsampled = false, TWELVE_BITS)
        checkNotNull(landed) { "matrix $matrix" }
        val expected = ycbcrToRgb(y, cb, cr, landed.matrix, 1, 13, true, 12).map { (it * EIGHT_BIT_STEP).toShort() }
        val named = ycbcrToRgb(y, cb, cr, matrix, 1, 13, true, 12).map { (it * EIGHT_BIT_STEP).toShort() }

        val decode = AppleStillSamples.twelveBit(nclx, heic = false, read = null, platform).single()
        assertContentEquals(expected, decode.take(RGB), "matrix $matrix on ${platform.label}")
        val readable = landed.matrix in AppleStillSamples.READABLE_MATRICES
        assertTrue(readable, "matrix $matrix lands through ${landed.matrix}")
        assertTrue(named != expected, "matrix $matrix reads as ${landed.matrix} does, so this cannot tell them apart")
      }
    }
  }

  @Test
  fun aTwelveBitStillRefusedBeforeDecodingHasNoDecode() {
    for (matrix in listOf(0, 3, 11, 14, 15)) {
      val nclx = CicpTag(1, 13, matrix, true)
      assertEquals(null, AppleStillSamples.landing(nclx, unsubsampled = false, TWELVE_BITS), "matrix $matrix lands")
      assertTrue(AppleStillSamples.twelveBit(nclx, heic = false, read = null, ApplePlatform.MacOs).isEmpty())
    }
  }

  @Test
  fun aTwelveBitModelRefusesAMatrixNoOneMeasured() {
    assertFailsWith<IllegalArgumentException> {
      AppleStillSamples.twelveBit(CicpTag(1, 13, 16, true), heic = false, read = null, ApplePlatform.MacOs)
    }
  }

  // The bitmap ImageIO hands back for every twelve-bit still, restated the way the model lands it, is sixteen bits a
  // sample and lands within rounding of its model, or is withheld where the model hands back nothing.
  @Test
  fun everyTwelveBitStillDecodesAtSixteenBitsWithinRoundingOfItsModel() {
    val misses =
      AppleStillSamples.TWELVE_BIT_MATRICES.flatMap { matrix ->
        listOf(true, false).flatMap { full ->
          val nclx = CicpTag(1, 13, matrix, full)
          listOf(
            TwelveBitStill("AVIF $nclx", AvifColourPictures.twelveBit(nclx).bytes(), heic = false),
            TwelveBitStill("HEIC $nclx", HeicColourPictures.twelveBit(nclx), heic = true),
          ).mapNotNull { still ->
            val landed = AppleStillSamples.landing(nclx, unsubsampled = false, TWELVE_BITS)
            AppleStillSamples.missOrUnmeasured {
              val read = landed?.let { imageIoDeepCentre(landedBytes(still.bytes, nclx, it.matrix)) }
              val texel = read?.let { r -> ShortArray(r.size) { r[it].toShort() } }
              val models = AppleStillSamples.twelveBit(nclx, still.heic, texel)
              twelveBitMiss(still.name, read, models)
            }
          }
        }
      }

    assertTrue(misses.isEmpty(), "Model misses on ${osBuild()}:\n" + misses.joinToString("\n"))
  }

  /**
   * A twelve-bit still, its bytes and whether it is a HEIC.
   */
  private class TwelveBitStill(
    val name: String,
    val bytes: ByteArray,
    val heic: Boolean,
  )

  private fun twelveBitMiss(
    name: String,
    read: List<Int>?,
    models: List<ShortArray>,
  ): String? {
    if (read == null) {
      val model = models.map { it.toList() }
      return if (models.isEmpty()) null else "$name handed out no samples, where its model is $model"
    }
    if (models.isEmpty()) return "$name decoded to $read, where its model hands out no samples"
    if (read.all { it % EIGHT_BIT_STEP == 0 }) return "$name decoded to $read, no deeper than eight bits"
    val codes = read.map { it / EIGHT_BIT_STEP.toFloat() }
    val near =
      models.any { model ->
        codes.indices.all { abs(codes[it] - (model[it].toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP.toFloat()) <= TOL }
      }
    return if (near) null else "$name decoded to $codes, where its models are ${models.map { it.toList() }}"
  }

  /**
   * The sixteen-bit red, green and blue ImageIO decodes [bytes] to at their centre, copied out of its own bitmap, or
   * null when ImageIO hands out none of the samples it decoded.
   */
  @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
  private fun imageIoDeepCentre(bytes: ByteArray): List<Int>? {
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val cf: CFDataRef = checkNotNull(CFBridgingRetain(data)).reinterpret()
    val source = checkNotNull(CGImageSourceCreateWithData(cf, null))
    CFRelease(cf)
    val image = checkNotNull(CGImageSourceCreateImageAtIndex(source, CGImageSourceGetPrimaryImageIndex(source), null))
    CFRelease(source)
    try {
      val info = CGImageGetBitmapInfo(image)
      assertTrue(
        CGImageGetBitsPerComponent(image) == DEEP_BITS.toULong() && CGImageGetBitsPerPixel(image) == 64uL &&
          info and kCGBitmapByteOrderMask == kCGBitmapByteOrder16Little && info and kCGBitmapAlphaInfoMask in RGB_FIRST,
        "ImageIO decoded to a layout this read does not take, bitmap info $info at ${CGImageGetBitsPerComponent(
          image,
        )}",
      )
      val samples = CGDataProviderCopyData(CGImageGetDataProvider(image)) ?: return null
      try {
        val pointer = checkNotNull(CFDataGetBytePtr(samples))
        val row = CGImageGetBytesPerRow(image).toInt()
        val centre = CGImageGetHeight(image).toInt() / 2 * row + CGImageGetWidth(image).toInt() / 2 * 2 * TEXEL_BYTES
        return List(RGB) {
          (pointer[centre + 2 * it].toInt() and BYTE) or ((pointer[centre + 2 * it + 1].toInt() and BYTE) shl BYTE_BITS)
        }
      } finally {
        CFRelease(samples)
      }
    } finally {
      CGImageRelease(image)
    }
  }

  private companion object {
    const val RGB = 3
    const val TEXEL_BYTES = 4
    const val BYTE = 0xFF
    const val DEEP_BITS = 16
    const val TWELVE_BITS = 12
    const val BYTE_BITS = 8
    const val TOL = AppleStillSamples.TOLERANCE
  }
}
