package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.media.heifWithRestatedColour
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
import platform.CoreGraphics.CGImageAlphaInfo
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
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGBitmapByteOrderMask
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.create
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import kotlin.experimental.ExperimentalNativeApi
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Holds [AppleStillSamples.asWritten] to failing where Apple's decode of a HEIC handed over as written is unmeasured on
 * every platform. That is a full range `nclx` over a codestream that signals limited range. Holds every HEIC and AVIF
 * decode on an iPhone to failing as unmeasured too, asked for on any host.
 *
 * Also holds every HEIC still, alone, as a grid and handed over as written, and every AVIF still alone and as a grid,
 * to the bitmap ImageIO decodes it to. That bitmap is what the overlay raster reads a HEIC or an AVIF from, so the
 * decodes [AppleStillSamples] lists for each, the two HEIC whole codes that are not [ycbcrToRgb]'s included, are
 * pinned against the decoder itself in one read. A miss names the OS build it was read on, since the decodes were
 * measured on one build of each platform. A still whose decode is unmeasured here is one more miss, so one run reports
 * every still.
 *
 * Every twelve-bit AVIF and HEIC under each matrix [AppleStillSamples.twelveBit] covers, at both ranges, restated the
 * way [AppleStillSamples.landing] lands it, is held to the sixteen-bit bitmap ImageIO decodes it to, or to ImageIO
 * handing out none of its samples.
 */
@OptIn(InternalFilmstripApi::class)
class AppleStillSamplesTest {
  private val limitedUnderFullNclx =
    HeicPicture(
      "HEIC nclx BT.709 full, VUI BT.709 limited",
      CicpTag(1, 13, 1, true),
      profile = null,
      ColourPictures.HEIC_NCLX_BT709_FULL.ycbcr,
      HevcVui.LimitedRangeBt709,
    )

  private val heics =
    ColourPictures.HEICS_WITH_SIGNAL_VARIANTS + AppleStillSamples.BT709_MATRIX_OTHER_PRIMARIES +
      ColourPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA

  private val avifs = ColourPictures.AVIFS + ColourPictures.NCLX_BT709_FULL_EDGE_CHROMA

  @Test
  fun failsAFullRangeNclxOverALimitedRangeCodestreamAsWritten() {
    val read = shortArrayOf(1, 2, 3, 4)

    val failure = assertFailsWith<AssertionError> { AppleStillSamples.asWritten(limitedUnderFullNclx, read) }

    assertContains(failure.message.orEmpty(), "unmeasured")
    assertContains(failure.message.orEmpty(), "handed over as written")
    assertContains(failure.message.orEmpty(), "${read.toList()}")
  }

  @Test
  fun failsEveryHeicAndAvifDecodeOnAnIphone() {
    val read = shortArrayOf(5, 6, 7, 8)
    val asked =
      heics.flatMap { heic ->
        listOf<Pair<String, () -> Unit>>(
          "$heic" to { AppleStillSamples.of(heic, read, ApplePlatform.Iphone) },
          "$heic as written" to { AppleStillSamples.asWritten(heic, read, ApplePlatform.Iphone) },
        )
      } + avifs.map { avif -> "AVIF $avif" to { AppleStillSamples.of(avif, read, ApplePlatform.Iphone) } }

    asked.forEach { (name, decode) ->
      val failure = assertFailsWith<AppleStillSamples.UnmeasuredDecode>("$name was measured on an iPhone") { decode() }
      assertContains(failure.message.orEmpty(), "unmeasured")
      assertContains(failure.message.orEmpty(), "iOS hardware")
      assertContains(failure.message.orEmpty(), "${read.toList()}")
    }
  }

  @Test
  fun failsAMatrixNoDecodeWasMeasuredUnder() {
    for (matrix in UNMEASURED_MATRICES) {
      val nclx = CicpTag(BT709_PRIMARIES, SRGB_TRANSFER, matrix, true)
      val avif = AvifPicture("AVIF nclx matrix $matrix", nclx, profile = null, ColourPictures.FLAT_YCBCR)
      val heic = HeicPicture("HEIC nclx matrix $matrix", nclx, profile = null, ColourPictures.FLAT_YCBCR)
      for (platform in listOf(ApplePlatform.MacOs, ApplePlatform.Simulator)) {
        val asked =
          listOf<Pair<String, () -> Unit>>(
            avif.name to { AppleStillSamples.of(avif, null, platform) },
            heic.name to { AppleStillSamples.of(heic, null, platform) },
            "${heic.name} as written" to { AppleStillSamples.asWritten(heic, null, platform) },
          )
        asked.forEach { (name, decode) ->
          val failure = assertFailsWith<IllegalArgumentException>("$name on ${platform.label}") { decode() }
          assertContains(failure.message.orEmpty(), "unmeasured")
        }
      }
    }
  }

  @Test
  fun anUnmeasuredDecodeIsOneMissAmongTheOthers() {
    val misses =
      (heics + limitedUnderFullNclx).mapNotNull { heic ->
        AppleStillSamples.missOrUnmeasured {
          AppleStillSamples.asWritten(heic, null, ApplePlatform.MacOs)
          null
        }
      }

    assertEquals(1, misses.size, "the unmeasured decodes were $misses")
    assertContains(misses.single(), limitedUnderFullNclx.name)
  }

  @Test
  fun anAssertionOtherThanAnUnmeasuredDecodeIsNotTurnedIntoAMiss() {
    val failure =
      assertFailsWith<AssertionError> {
        AppleStillSamples.missOrUnmeasured { fail("a plain failure") }
      }

    assertFalse(failure is AppleStillSamples.UnmeasuredDecode, "the plain failure became $failure")
    assertEquals("a plain failure", failure.message)
  }

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

  // The still with its codestream and nclx stating matrix, the way the overlay raster hands it to ImageIO.
  private fun landedBytes(
    bytes: ByteArray,
    nclx: CicpTag,
    matrix: Int,
  ): ByteArray {
    if (matrix == nclx.matrix) return heifWithExplicitCodestreamColour(bytes)
    val restated = heifWithRestatedColour(bytes) { _, _, _ -> RestatedColour(matrix, nclx.primaries, nclx = true) }
    assertTrue(restated !== bytes, "the still with nclx $nclx was not restated to matrix $matrix")
    return restated
  }

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

  @Test
  fun heicDecodeOnMacOsIsTheWholeCodesYcbcrToRgbGives() {
    if (!onMacOs()) return
    val still = ColourPictures.HEIC_NCLX_BT601_FULL
    val decodes = AppleStillSamples.of(still)
    assertEquals(1, decodes.size, "$still has more than one decode")

    val (y, cb, cr) = still.ycbcr
    val nclx = checkNotNull(still.nclx)
    val rounded =
      ycbcrToRgb(y, cb, cr, nclx.matrix, nclx.primaries, nclx.transfer, nclx.fullRange)
        .map { (it * EIGHT_BIT_STEP).toShort() }
    assertContentEquals(rounded, decodes.single().take(RGB))
  }

  @Test
  fun everyHeicDecodesToTheWholeCodesImageIoGives() {
    val misses =
      heics.flatMap { heic ->
        listOfNotNull(
          miss("$heic", heifWithExplicitCodestreamColour(heic.bytes())) { AppleStillSamples.of(heic, it) },
          miss("$heic grid", heifWithExplicitCodestreamColour(heic.gridBytes())) { AppleStillSamples.of(heic, it) },
          miss("$heic as written", heic.bytes()) { AppleStillSamples.asWritten(heic, it) },
        )
      }

    assertTrue(misses.isEmpty(), "Model misses on ${osBuild()}:\n" + misses.joinToString("\n"))
  }

  @Test
  fun aHeicUnderAnUnreadMatrixDecodesThroughItsLandingToItsModel() {
    val still = AppleStillSamples.UNREAD_MATRIX
    val nclx = checkNotNull(still.nclx)
    val landed = checkNotNull(AppleStillSamples.landing(nclx, unsubsampled = false, EIGHT_BITS))
    assertFalse(landed.exact, "$still lands exactly through matrix ${landed.matrix}")

    val miss = miss("$still", landedBytes(still.bytes(), nclx, landed.matrix)) { AppleStillSamples.landed(still, it) }

    assertEquals(null, miss, "Model miss on ${osBuild()}")
  }

  @Test
  fun everyAvifDecodesWithinRoundingOfItsModel() {
    val misses =
      avifs.flatMap { avif ->
        listOfNotNull(
          miss("AVIF $avif", heifWithExplicitCodestreamColour(avif.bytes())) { AppleStillSamples.of(avif, it) },
          miss(
            "AVIF $avif grid",
            heifWithExplicitCodestreamColour(avif.gridBytes()),
          ) { AppleStillSamples.of(avif, it) },
        )
      }

    assertTrue(misses.isEmpty(), "Model misses on ${osBuild()}:\n" + misses.joinToString("\n"))
  }

  private fun miss(
    name: String,
    bytes: ByteArray,
    decodes: (ShortArray) -> List<ShortArray>,
  ): String? =
    AppleStillSamples.missOrUnmeasured {
      val read = imageIoCentre(bytes)
      val model =
        decodes(ShortArray(RGB) { (read[it] * EIGHT_BIT_STEP).toShort() }).single().take(RGB).map {
          (it.toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP.toFloat()
        }
      val near = read.indices.all { abs(read[it] - model[it]) <= AppleStillSamples.TOLERANCE }
      if (near) null else "$name decoded to $read through ImageIO, where its model is $model"
    }

  /**
   * The eight-bit red, green and blue ImageIO decodes [bytes] to at their centre, copied out of its own bitmap.
   */
  @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
  private fun imageIoCentre(bytes: ByteArray): List<Int> {
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val cf: CFDataRef = checkNotNull(CFBridgingRetain(data)).reinterpret()
    val source = checkNotNull(CGImageSourceCreateWithData(cf, null))
    CFRelease(cf)
    val image = checkNotNull(CGImageSourceCreateImageAtIndex(source, CGImageSourceGetPrimaryImageIndex(source), null))
    CFRelease(source)
    try {
      val info = CGImageGetBitmapInfo(image)
      val alpha = info and kCGBitmapAlphaInfoMask
      val order = info and kCGBitmapByteOrderMask
      assertTrue(
        CGImageGetBitsPerComponent(image) == 8uL && CGImageGetBitsPerPixel(image) == 32uL &&
          (order == 0u || order == kCGBitmapByteOrder32Big) && alpha in RGB_FIRST,
        "ImageIO decoded to a layout this read does not take, bitmap info $info",
      )
      val samples = checkNotNull(CGDataProviderCopyData(CGImageGetDataProvider(image)))
      try {
        val pointer = checkNotNull(CFDataGetBytePtr(samples))
        val row = CGImageGetBytesPerRow(image).toInt()
        val centre = CGImageGetHeight(image).toInt() / 2 * row + CGImageGetWidth(image).toInt() / 2 * TEXEL_BYTES
        return List(RGB) { pointer[centre + it].toInt() and BYTE }
      } finally {
        CFRelease(samples)
      }
    } finally {
      CGImageRelease(image)
    }
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

  @OptIn(ExperimentalNativeApi::class)
  private fun onMacOs(): Boolean = Platform.osFamily == OsFamily.MACOSX

  private fun osBuild(): String =
    "${NSProcessInfo.processInfo.operatingSystemVersionString}${if (isSimulator()) " simulator" else ""}"

  private companion object {
    const val RGB = 3
    const val BT709_PRIMARIES = 1
    const val SRGB_TRANSFER = 13

    // Identity, YCgCo, BT.2020 constant luminance and the two matrices derived from chromaticities, 12 on these
    // primaries reading as BT.709 does.
    val UNMEASURED_MATRICES = listOf(0, 8, 10, 12, 13)
    const val TEXEL_BYTES = 4
    const val BYTE = 0xFF
    const val SAMPLE_MASK = 0xFFFF
    const val EIGHT_BIT_STEP = 257
    const val DEEP_BITS = 16
    const val TWELVE_BITS = 12
    const val EIGHT_BITS = 8
    const val BYTE_BITS = 8
    const val TOL = AppleStillSamples.TOLERANCE

    // The alpha layouts that put red, green and blue in the first three bytes of a texel.
    val RGB_FIRST =
      listOf(
        CGImageAlphaInfo.kCGImageAlphaLast,
        CGImageAlphaInfo.kCGImageAlphaNoneSkipLast,
        CGImageAlphaInfo.kCGImageAlphaPremultipliedLast,
      ).map { it.value }
  }
}
