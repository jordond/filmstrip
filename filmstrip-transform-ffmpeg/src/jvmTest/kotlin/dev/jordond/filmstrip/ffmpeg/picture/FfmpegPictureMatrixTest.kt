package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureRoute
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.AvifColorPictures
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeicColorPictures
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.ycbcrToRgb
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * AVIF and HEIC overlays tagged with the matrix coefficients code points core reads, each decoded at eight and twelve
 * bits and held at its centre texel against [ycbcrToRgb].
 *
 * Each run reads one [MatrixCase] of [MatrixCases.all], the list [FfmpegPictureMatrixRouteTest] holds to the route its
 * table names for each tag.
 *
 * A matrix swscale names lands through swscale within [PICTURE_DRIFT]. YCgCo 8, constant luminance 10 and 13, and 12
 * on primaries no named matrix matches land from their planes through core's evaluator, exactly at eight bits and
 * within one code at twelve, where the sixteen-bit samples are rounded once more. Each of those is checked to land
 * elsewhere under BT.601, the matrix swscale falls back to. Identity has its own test, since it needs 4:4:4 samples.
 */
@RunWith(Parameterized::class)
class FfmpegPictureMatrixTest internal constructor(
  private val case: MatrixCase,
) {
  @Test
  fun `lands where core reads it`() {
    case.stills().forEach(::assertLanded)
  }

  // [still] decoded and held at its centre against [ycbcrToRgb] of its codes through the coding core reads for it.
  private fun assertLanded(still: MatrixStill) {
    val name = still.name
    val bitDepth = still.bitDepth
    val coding = withClue(name) { pictureYcbcrCoding(still.bytes).shouldNotBeNull() }
    withClue(name) { coding.bitDepth shouldBe bitDepth }
    val (y, cb, cr) = still.ycbcr
    val expected =
      ycbcrToRgb(y, cb, cr, coding.matrix, coding.primaries, coding.transfer, coding.fullRange, bitDepth).toList()
    val planes = pictureRoute(coding) is PictureRoute.Planes
    val drift =
      if (planes) {
        val bt601 = ycbcrToRgb(y, cb, cr, BT601, coding.primaries, coding.transfer, coding.fullRange, bitDepth).toList()
        val apart = expected.indices.any { abs(expected[it] - bt601[it]) > PICTURE_DRIFT }
        withClue("$name reads alike as BT.601") { apart.shouldBeTrue() }
        if (bitDepth == EIGHT_BITS) 0 else 1
      } else {
        PICTURE_DRIFT
      }
    val decoded = decoded(still.bytes)
    val wide = decoded.samples is StoredSamples.Rgba16
    withClue("$name decoded at the wrong depth") { wide shouldBe (bitDepth > EIGHT_BITS) }
    val landed = centreOf(decoded)
    val near = landed.indices.all { abs(landed[it] - expected[it]) <= drift }
    withClue("$name decoded $landed, not $expected within $drift") { near.shouldBeTrue() }
  }

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    val refused = (read as? OverlayPictureRead.Refused)?.reason ?: (read as? OverlayPictureDecode.Undrawn)?.reason
    return withClue("this ffmpeg decoded nothing: $refused") {
      (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
    }
  }

  // The red, green and blue of [decoded]'s centre texel in eight-bit codes.
  private fun centreOf(decoded: DecodedPicture): List<Int> {
    val at = decoded.size.height / 2 * decoded.size.width + decoded.size.width / 2
    return when (val samples = decoded.samples) {
      is StoredSamples.Argb -> {
        listOf(samples.argb[at] shr 16, samples.argb[at] shr 8, samples.argb[at]).map { it and 0xFF }
      }
      is StoredSamples.Rgba16 -> {
        (0 until 3).map { ((samples.rgba[at * 4 + it].toInt() and SIXTEEN_BIT_MAX) / WIDE_PER_CODE).roundToInt() }
      }
    }
  }

  /**
   * The cases the matrix test runs, each run named for its container, tag and range, and the decoder every run reads
   * through.
   */
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    internal fun cases(): List<MatrixCase> = MatrixCases.all

    private val decoder = FfmpegPictureDecoder("ffmpeg")

    private const val BT601 = 6
    private const val SIXTEEN_BIT_MAX = 0xFFFF

    // An eight-bit code at sixteen bits.
    private const val WIDE_PER_CODE = 257.0
  }
}

// A tag for every matrix coefficients code point core lands but identity, on the primaries and transfer it is read
// beside. Each is at full range, and a case that reads one at limited range retags it.
internal object MatrixTags {
  val BT709 = CicpTag(1, 13, 1, true)
  val UNSPECIFIED = CicpTag(1, 13, 2, true)
  val FCC = CicpTag(1, 13, 4, true)
  val BT470BG = CicpTag(1, 13, 5, true)
  val BT601 = CicpTag(1, 13, 6, true)
  val SMPTE_240M = CicpTag(1, 13, 7, true)
  val YCGCO = CicpTag(1, 13, 8, true)
  val BT2020 = CicpTag(9, 13, 9, true)
  val BT2020_CL = CicpTag(9, 1, 10, true)
  val DERIVED_ON_BT709 = CicpTag(1, 13, 12, true)
  val DERIVED_ON_BT2020 = CicpTag(9, 13, 12, true)
  val DERIVED_ON_P3 = CicpTag(12, 13, 12, true)
  val DERIVED_CL_ON_BT2020 = CicpTag(9, 1, 13, true)
  val DERIVED_CL_ON_P3 = CicpTag(12, 13, 13, true)
}

// The container a matrix case wraps its still in.
internal enum class MatrixContainer(
  val label: String,
) {
  Avif("AVIF"),
  Heic("HEIC"),
}

// The eight- and twelve-bit stills of [tag] in [container] at [fullRange], one run of the matrix test.
internal data class MatrixCase(
  val container: MatrixContainer,
  val tag: CicpTag,
  val fullRange: Boolean,
) {
  fun stills(): List<MatrixStill> =
    when (container) {
      MatrixContainer.Avif -> avifStills(tag, fullRange)
      MatrixContainer.Heic -> heicStills(tag, fullRange)
    }

  override fun toString(): String {
    val range = if (fullRange) "full" else "limited"
    return "${container.label} tagged ${tag.primaries}/${tag.transfer}/${tag.matrix} at $range range"
  }
}

// Every case the matrix test runs. Each tag is read from an AVIF at full range. One tag for each route is read from a
// HEIC at both ranges. One tag for each route, and one for each matrix read from its planes, is read from an AVIF at
// limited range.
internal object MatrixCases {
  val all =
    listOf(
      avif(MatrixTags.BT709),
      avif(MatrixTags.BT709, fullRange = false),
      heic(MatrixTags.BT709),
      heic(MatrixTags.BT709, fullRange = false),
      avif(MatrixTags.UNSPECIFIED),
      avif(MatrixTags.FCC),
      avif(MatrixTags.BT470BG),
      avif(MatrixTags.BT601),
      avif(MatrixTags.SMPTE_240M),
      avif(MatrixTags.YCGCO),
      avif(MatrixTags.YCGCO, fullRange = false),
      heic(MatrixTags.YCGCO),
      heic(MatrixTags.YCGCO, fullRange = false),
      avif(MatrixTags.BT2020),
      avif(MatrixTags.BT2020_CL),
      avif(MatrixTags.BT2020_CL, fullRange = false),
      heic(MatrixTags.BT2020_CL),
      heic(MatrixTags.BT2020_CL, fullRange = false),
      avif(MatrixTags.DERIVED_ON_BT709),
      avif(MatrixTags.DERIVED_ON_BT2020),
      avif(MatrixTags.DERIVED_ON_P3),
      avif(MatrixTags.DERIVED_ON_P3, fullRange = false),
      heic(MatrixTags.DERIVED_ON_P3),
      heic(MatrixTags.DERIVED_ON_P3, fullRange = false),
      avif(MatrixTags.DERIVED_CL_ON_BT2020),
      avif(MatrixTags.DERIVED_CL_ON_P3),
      avif(MatrixTags.DERIVED_CL_ON_P3, fullRange = false),
    )

  private fun avif(
    tag: CicpTag,
    fullRange: Boolean = true,
  ) = MatrixCase(MatrixContainer.Avif, tag, fullRange)

  private fun heic(
    tag: CicpTag,
    fullRange: Boolean = true,
  ) = MatrixCase(MatrixContainer.Heic, tag, fullRange)
}

// A still the matrix tests read, with the codes the sample at its centre holds and the depth they are stored at.
internal class MatrixStill(
  val name: String,
  val bytes: ByteArray,
  val ycbcr: List<Int>,
  val bitDepth: Int,
)

// The eight-bit flat AVIF item tagged [tag] at [fullRange], with the codestream whose range bit matches, and the
// twelve-bit AVIF.
internal fun avifStills(
  tag: CicpTag,
  fullRange: Boolean,
): List<MatrixStill> {
  val nclx = CicpTag(tag.primaries, tag.transfer, tag.matrix, fullRange)
  val picture = if (nclx.fullRange) ColorPictures.NCLX_UNSPECIFIED else ColorPictures.NCLX_BT709_LIMITED
  val item = picture.thumbnail()
  val eightBit = HeifStills.still("avif", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
  return listOf(
    MatrixStill("AVIF $nclx", eightBit, FLAT_YCBCR, EIGHT_BITS),
    MatrixStill(
      "twelve-bit AVIF $nclx",
      AvifColorPictures.twelveBit(nclx).bytes(),
      AvifColorPictures.TWELVE_BIT_YCBCR,
      TWELVE_BITS,
    ),
  )
}

// The eight-bit flat HEIC item tagged [tag] at [fullRange], whose codestream signals no range or color, and the
// twelve-bit HEIC.
internal fun heicStills(
  tag: CicpTag,
  fullRange: Boolean,
): List<MatrixStill> {
  val nclx = CicpTag(tag.primaries, tag.transfer, tag.matrix, fullRange)
  val item = ColorPictures.HEIC_NCLX_BT709_FULL.thumbnail()
  val eightBit = HeifStills.still("heic", item.itemType, item.configuration, item.codestream, item.size, nclx = nclx)
  return listOf(
    MatrixStill("HEIC $nclx", eightBit, FLAT_YCBCR, EIGHT_BITS),
    MatrixStill(
      "twelve-bit HEIC $nclx",
      HeicColorPictures.twelveBit(nclx),
      HeicColorPictures.TWELVE_BIT_YCBCR,
      TWELVE_BITS,
    ),
  )
}

// The codes every sample of the eight-bit AVIF and HEIC items decodes to.
private val FLAT_YCBCR = ColorPictures.NCLX_UNSPECIFIED.ycbcr

private const val EIGHT_BITS = 8
private const val TWELVE_BITS = 12
