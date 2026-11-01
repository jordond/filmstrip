@file:OptIn(ExperimentalWasmJsInterop::class, InternalFilmstripApi::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.IDENTITY_MATRIX
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.NearestMatrix
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.media.equivalentCode
import dev.jordond.filmstrip.media.nearestReadable
import dev.jordond.filmstrip.media.rgbMatrix
import dev.jordond.filmstrip.media.ycbcrReading
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ycbcrToRgb
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.unsafeCast
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every ITU-T H.273 matrix an AVIF overlay can name, at both ranges, at eight, ten and twelve bits on 4:2:0 and at
 * eight and ten on 4:4:4, resolved by the browser resolver and read back.
 *
 * A picture that resolves lands where core's `ycbcrToRgb` puts its samples under its own matrix, within Chrome's own
 * drift on BT.709 and BT.2020 at limited range. One that degrades lands where it puts them under the nearest matrix
 * Chrome reads, and names that matrix and core's figures. One that is refused carries core's refusal or says the
 * nearest matrix lands it past the bound. A picture stored deeper than eight bits also reports Chrome's eight-bit
 * decode, after any matrix it lands through.
 */
class WebOverlayMatrixTest {
  @Test
  fun everyFullRangeEightBit420MatrixLandsWhereCoreReadsIt() =
    assertEveryMatrixLands(MatrixAvif.EIGHT_BIT_420, fullRange = true)

  @Test
  fun everyLimitedRangeEightBit420MatrixLandsWhereCoreReadsIt() =
    assertEveryMatrixLands(MatrixAvif.EIGHT_BIT_420, fullRange = false)

  @Test
  fun everyFullRangeEightBit444MatrixLandsWhereCoreReadsIt() =
    assertEveryMatrixLands(MatrixAvif.EIGHT_BIT_444, fullRange = true)

  @Test
  fun everyLimitedRangeEightBit444MatrixLandsWhereCoreReadsIt() =
    assertEveryMatrixLands(MatrixAvif.EIGHT_BIT_444, fullRange = false)

  @Test
  fun everyFullRangeTenBit420MatrixLandsWhereCoreReadsIt() =
    assertEveryMatrixLands(MatrixAvif.TEN_BIT_420, fullRange = true)

  @Test
  fun everyLimitedRangeTenBit420MatrixLandsWhereCoreReadsIt() =
    assertEveryMatrixLands(MatrixAvif.TEN_BIT_420, fullRange = false)

  @Test
  fun everyFullRangeTenBit444MatrixLandsWhereCoreReadsIt() =
    assertEveryMatrixLands(MatrixAvif.TEN_BIT_444, fullRange = true)

  @Test
  fun everyLimitedRangeTenBit444MatrixLandsWhereCoreReadsIt() =
    assertEveryMatrixLands(MatrixAvif.TEN_BIT_444, fullRange = false)

  @Test
  fun everyFullRangeTwelveBit420MatrixLandsWhereCoreReadsIt() =
    assertEveryMatrixLands(MatrixAvif.TWELVE_BIT_420, fullRange = true)

  @Test
  fun everyLimitedRangeTwelveBit420MatrixLandsWhereCoreReadsIt() =
    assertEveryMatrixLands(MatrixAvif.TWELVE_BIT_420, fullRange = false)

  @Test
  fun aLimitedRangeBt709PictureResolvesThroughChromesOwnDecode() =
    runTest {
      val bytes = MatrixAvif.EIGHT_BIT_420.bytes(CicpTag(BT709, SRGB_TRANSFER, BT709, fullRange = false))
      val resolved = assertIs<EffectResolution.Resolved>(resolve(bytes))

      assertNull(overlayOf(resolved.effect.overlay).samples)
    }

  @Test
  fun chromeDecodesLimitedRangeBt709AndBt2020WithinItsOwnDrift() =
    runTest {
      val worst = limitedRangeDrift()

      assertTrue(worst <= CHROME_LIMITED_RANGE_DRIFT, "Chrome's limited range drift grew to $worst")
    }

  /**
   * The furthest Chrome's own decode of a limited range picture under BT.709, BT.2020 or a matrix derived from either
   * sits from core's reading, in eight-bit codes, over every picture depth and layout.
   */
  internal suspend fun limitedRangeDrift(): Int {
    var worst = 0
    for (picture in PICTURES) {
      for (tag in listOf(BT709 to BT709, BT709 to BT2020, BT709 to DERIVED, BT2020 to DERIVED)) {
        val case = Case(picture, CicpTag(tag.first, SRGB_TRANSFER, tag.second, fullRange = false))
        worst = maxOf(worst, chromeMiss(case, drawnOverlay(case, resolve(case.bytes())), tag.second))
      }
    }
    return worst
  }

  @Test
  fun aTenBit444ConstantLuminancePictureTakesTheNearestMatrixSinceACanvasReadsEightBits() =
    runTest {
      val bytes = MatrixAvif.TEN_BIT_444.bytes(CicpTag(BT709, SRGB_TRANSFER, BT2020_CONSTANT, fullRange = true))
      val nearest = assertNotNull(nearestOf(assertNotNull(pictureYcbcrCoding(bytes))))
      val degraded = assertIs<EffectResolution.Degraded>(resolve(bytes))

      assertEquals(BT2020, nearest.matrix)
      assertNull(overlayOf(degraded.effect.overlay).samples)
      assertEquals(listOf(unreadMatrix(nearest), eightBitDecode(10)), degraded.causes)
    }

  @Test
  fun aPictureDeeperThanEightBitsReportsChromesEightBitDecodeAsPrecisionReduced() =
    runTest {
      for (picture in listOf(MatrixAvif.TEN_BIT_420, MatrixAvif.TEN_BIT_444, MatrixAvif.TWELVE_BIT_420)) {
        val bytes = picture.bytes(CicpTag(BT709, SRGB_TRANSFER, BT601, fullRange = true))
        val cause = assertIs<EffectResolution.Degraded>(resolve(bytes), "$picture").causes.single()

        assertEquals(DegradationReason.PrecisionReduced, cause.reason, "$picture")
        assertEquals(eightBitDecode(picture.bitDepth), cause, "$picture")
      }
    }

  @Test
  fun aLimitedRangeYcgcoPictureIsRefusedPastTheBoundWithCoresFigures() =
    runTest {
      val bytes = MatrixAvif.EIGHT_BIT_420.bytes(CicpTag(BT709, SRGB_TRANSFER, YCGCO, fullRange = false))
      val nearest = assertNotNull(nearestOf(assertNotNull(pictureYcbcrCoding(bytes))))
      val unsupported = assertIs<EffectResolution.Unsupported>(resolve(bytes))

      assertFalse(nearest.withinBound)
      assertEquals(
        "Chrome's AVIF decoder does not read this picture's matrix at its range, and the nearest one it reads, " +
          "matrix ${nearest.matrix}, would turn its YCbCr samples into RGB with ${nearest.figures}.",
        unsupported.message,
      )
    }

  @Test
  fun aMatrixCoreRefusesIsRefusedWithCoresReason() =
    runTest {
      for (matrix in listOf(RESERVED, YDZDX, ICTCP)) {
        val bytes = MatrixAvif.EIGHT_BIT_444.bytes(CicpTag(BT709, SRGB_TRANSFER, matrix, fullRange = true))
        val refusal = assertNotNull(assertNotNull(pictureYcbcrCoding(bytes)).refusal)

        assertEquals(refusal, assertIs<EffectResolution.Unsupported>(resolve(bytes)).message, "matrix $matrix")
      }
    }

  // Every case lands where its route says it does, and the routes that are not Chrome's own decode are the ones its
  // limits force: the nearest matrix past 4:4:4 at eight bits, and core's evaluator on it.
  private fun assertEveryMatrixLands(
    picture: MatrixAvif,
    fullRange: Boolean,
  ) = runTest {
    val misses = mutableListOf<String>()
    val forced = mutableMapOf<Pair<Int, Int>, String>()
    for (tag in CASES) {
      val case = Case(picture, CicpTag(tag.first, SRGB_TRANSFER, tag.second, fullRange))
      val (route, miss) = outcomeOf(case)
      miss?.let { misses += "$case: $it" }
      if (route != RESOLVED && route != REFUSED) forced[tag] = route
    }
    val limits = if (fullRange) FULL_RANGE_LIMITS else LIMITED_RANGE_LIMITS
    val expected = if (picture.unsubsampled && picture.bitDepth == 8) limits.mapValues { EVALUATED } else limits

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    assertEquals(expected, forced, "$picture at ${if (fullRange) "full" else "limited"} range")
  }

  private suspend fun outcomeOf(case: Case): Pair<String, String?> {
    val bytes = case.bytes()
    val coding = assertNotNull(pictureYcbcrCoding(bytes), "$case")
    return when (val resolution = resolve(bytes)) {
      is EffectResolution.Unsupported -> {
        val refusal = coding.refusal
        val nearest = if (resolution.message == refusal) null else nearestOf(coding)
        val pastTheBound = nearest != null && !nearest.withinBound && resolution.message == pastBound(nearest)
        val ok = resolution.message == refusal || pastTheBound
        val route = if (resolution.message == refusal) REFUSED else PAST_BOUND
        route to if (ok) null else "refused with ${resolution.message}"
      }
      is EffectResolution.Degraded -> {
        val cut = cutOf(case.picture)
        val overlay = overlayOf(resolution.effect.overlay)
        val causes = resolution.causes
        if (causes.takeLast(cut.size) != cut) return DEGRADED_VIA to "degraded without its eight-bit decode, $causes"
        val colour = causes.dropLast(cut.size)
        if (colour.isEmpty()) return drawn(case, overlay)
        val landed = assertNotNull(nearestOf(coding), "$case")
        val miss =
          when {
            !landed.withinBound -> "degraded past the bound"
            colour.singleOrNull() != unreadMatrix(landed) -> "degraded with ${colour.map { it.message }}"
            else -> landingMiss(case, overlay, landed.matrix)
          }
        "$DEGRADED_VIA${landed.matrix}" to miss
      }
      is EffectResolution.Resolved -> {
        val (route, miss) = drawn(case, overlayOf(resolution.effect.overlay))
        val unreported = "resolved without its eight-bit decode".takeIf { cutOf(case.picture).isNotEmpty() }
        route to (miss ?: unreported)
      }
      null -> {
        "none" to "did not resolve"
      }
    }
  }

  // How a picture that lands through its own matrix is drawn, by Chrome's decode or core's evaluator.
  private fun drawn(
    case: Case,
    overlay: WebGlOverlay,
  ): Pair<String, String?> {
    val route = if (overlay.samples != null) EVALUATED else RESOLVED
    return route to landingMiss(case, overlay, case.nclx.matrix)
  }

  // The overlay of a picture Chrome decodes through its own matrix, which reports nothing but its eight-bit decode.
  private fun drawnOverlay(
    case: Case,
    resolution: EffectResolution?,
  ): WebGlOverlay {
    val cut = cutOf(case.picture)
    if (cut.isEmpty()) return overlayOf(assertIs<EffectResolution.Resolved>(resolution, "$case").effect.overlay)
    val degraded = assertIs<EffectResolution.Degraded>(resolution, "$case")
    assertEquals(cut, degraded.causes, "$case")
    return overlayOf(degraded.effect.overlay)
  }

  private fun cutOf(picture: MatrixAvif): List<Degradation> =
    listOfNotNull(eightBitDecode(picture.bitDepth).takeIf { picture.bitDepth > EVALUATED_DEPTH })

  // Where the overlay's codes sit from core's decode of each block under matrix, or null when every block lands. An
  // evaluated picture carries core's own decode, so it is held to its rounding over every block. Chrome's decode is
  // held to CHROME_ROUNDING, or to Chrome's own drift on the matrices it reads off H.273 at limited range.
  private fun landingMiss(
    case: Case,
    overlay: WebGlOverlay,
    matrix: Int,
  ): String? {
    val picture = case.picture
    val nclx = case.nclx
    val samples = overlay.samples
    if (samples == null) {
      val miss = chromeMiss(case, overlay, matrix)
      val drifts = !nclx.fullRange && ycbcrReading(matrix, nclx.primaries, nclx.transfer).equivalentCode() in DRIFTING
      val tolerance = if (drifts) CHROME_LIMITED_RANGE_DRIFT else CHROME_ROUNDING
      return if (miss > tolerance) "decoded $miss codes from core's reading" else null
    }
    for (block in 0 until MatrixAvif.BLOCKS) {
      val (y, cb, cr) = picture.samples(block).toList()
      val expected = ycbcrToRgb(y, cb, cr, matrix, nclx.primaries, nclx.transfer, nclx.fullRange, picture.bitDepth)
      val texel = picture.texelOf(block) * RGBA
      val read = DoubleArray(3) { (samples[texel + it].toInt() and SAMPLE_MASK) / SAMPLE_PER_CODE }
      if ((0 until 3).any { abs(read[it] - expected[it]) > EVALUATED_ROUNDING }) {
        return "block $block evaluated ${read.toList()} for ${expected.toList()}"
      }
    }
    return null
  }

  // The furthest Chrome's decode of the overlay sits from core's decode under matrix, in eight-bit codes, over the
  // blocks whose colour core puts inside the gamut, since Chrome clamps on the way.
  private fun chromeMiss(
    case: Case,
    overlay: WebGlOverlay,
    matrix: Int,
  ): Int {
    val picture = case.picture
    val nclx = case.nclx
    val codes = assertNotNull(codesOf(overlay.image.unsafeCast<ImageBitmap>()))
    val unclamped = ycbcrReading(matrix, nclx.primaries, nclx.transfer).rgbMatrix(picture.bitDepth, nclx.fullRange)
    var worst = 0
    for (block in 0 until MatrixAvif.BLOCKS) {
      val (y, cb, cr) = picture.samples(block).toList()
      val light = unclamped?.rgb(y.toDouble(), cb.toDouble(), cr.toDouble())
      if (light != null && light.any { it < -GAMUT_SLACK || it > 1 + GAMUT_SLACK }) continue
      val expected = ycbcrToRgb(y, cb, cr, matrix, nclx.primaries, nclx.transfer, nclx.fullRange, picture.bitDepth)
      val texel = picture.texelOf(block) * RGBA
      for (channel in 0 until 3) {
        worst = maxOf(worst, abs((codes[texel + channel].toInt() and BYTE_MASK) - expected[channel]))
      }
    }
    return worst
  }

  private fun nearestOf(coding: YcbcrCoding): NearestMatrix? {
    val readable = chromeMatrices(coding.fullRange).let { if (coding.unsubsampled) it else it - IDENTITY_MATRIX }
    return coding.reading.nearestReadable(readable)
  }

  private suspend fun resolve(bytes: ByteArray): EffectResolution? {
    val image = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)
    val resolver = BuiltInEffectResolver()
    resolver.preload(EditComposition(listOf(Track(listOf(Clip(MediaSource.ofUri(CLIP), effects = listOf(image)))))))
    return resolver.resolve(image, CAPABILITIES, ATTRIBUTES)
  }

  private fun overlayOf(overlay: WebGlOverlay?): WebGlOverlay = assertNotNull(overlay)

  private class Case(
    val picture: MatrixAvif,
    val nclx: CicpTag,
  ) {
    fun bytes(): ByteArray = picture.bytes(nclx)

    override fun toString(): String = "$picture nclx $nclx"
  }

  internal companion object {
    const val SRGB_TRANSFER = 13
    const val BT709 = 1
    const val BT601 = 6
    const val BT2020 = 9
    const val DISPLAY_P3 = 12
    const val RESERVED = 3
    const val YCGCO = 8
    const val BT2020_CONSTANT = 10
    const val YDZDX = 11
    const val DERIVED = 12
    const val DERIVED_CONSTANT = 13
    const val ICTCP = 14

    const val RESOLVED = "resolved"
    const val REFUSED = "refused"
    const val EVALUATED = "evaluated"
    const val PAST_BOUND = "refused past the bound"
    const val DEGRADED_VIA = "degraded via "

    // The primaries and matrix of each case Chrome cannot decode through its own matrix at full range, and how it
    // lands on 4:2:0 and at ten bits. On 4:4:4 at eight bits each is evaluated instead.
    val FULL_RANGE_LIMITS: Map<Pair<Int, Int>, String> =
      mapOf(
        (BT709 to BT2020_CONSTANT) to "${DEGRADED_VIA}9",
        (BT709 to DERIVED_CONSTANT) to "${DEGRADED_VIA}1",
        (DISPLAY_P3 to DERIVED_CONSTANT) to "${DEGRADED_VIA}1",
        (BT2020 to DERIVED_CONSTANT) to "${DEGRADED_VIA}9",
      )

    // The same at limited range, where Chrome decodes no YCgCo either.
    val LIMITED_RANGE_LIMITS: Map<Pair<Int, Int>, String> = FULL_RANGE_LIMITS + mapOf((BT709 to YCGCO) to PAST_BOUND)

    // Every matrix on BT.709 primaries, and the two derived from primaries on primaries a named matrix matches and on
    // Display P3, which none does.
    val PICTURES =
      listOf(
        MatrixAvif.EIGHT_BIT_420,
        MatrixAvif.EIGHT_BIT_444,
        MatrixAvif.TEN_BIT_420,
        MatrixAvif.TEN_BIT_444,
        MatrixAvif.TWELVE_BIT_420,
      )

    val CASES: List<Pair<Int, Int>> =
      (0..ICTCP).map { BT709 to it } +
        listOf(DISPLAY_P3 to DERIVED, BT2020 to DERIVED, DISPLAY_P3 to DERIVED_CONSTANT, BT2020 to DERIVED_CONSTANT)

    // How far Chrome's own decode may sit from core's, in eight-bit codes per channel, on colours inside the gamut.
    // Measured at 2 on twelve-bit BT.601 at limited range and at 1 or less elsewhere.
    const val CHROME_ROUNDING = 2

    // Chrome's own limit. It turns limited range BT.709 and BT.2020 samples into RGB with coefficients coarser than
    // H.273's, so on colours inside the gamut its decode sits up to this many eight-bit codes from core's.
    const val CHROME_LIMITED_RANGE_DRIFT = 6
    val DRIFTING = setOf(BT709, BT2020)

    // An evaluated sample is core's decode at sixteen bits, so it sits within half a code of core's rounded one.
    const val EVALUATED_ROUNDING = 0.51
    const val GAMUT_SLACK = 0.002
    const val SAMPLE_PER_CODE = 257.0
    const val SAMPLE_MASK = 0xFFFF
    const val BYTE_MASK = 0xFF
    const val RGBA = 4
    const val CLIP = "blob:clip"

    val FRAME = Size(1280, 720)
    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.WebGl,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = false,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 4_096,
        features = emptySet(),
      )
    val ATTRIBUTES =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 10.seconds),
      )
  }
}
