package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.NearestMatrix
import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.evaluator
import dev.jordond.filmstrip.color.nearestReadable
import dev.jordond.filmstrip.color.rgbMatrix
import dev.jordond.filmstrip.color.ycbcrReading
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The RGB samples a [ColorPictures] AVIF or HEIC still decodes to on Apple, before any color management, to within
 * [TOLERANCE].
 *
 * Apple reads full range chroma over 254 codes rather than 255, the 1 to 255 CoreVideo's full range YCbCr formats
 * span. That is CoreVideo's limit, not the still's, so a full range still lands up to a third of a code off its
 * ITU-T H.273 reading on most of these stills and most of a code on [ColorPictures.NCLX_BT709_FULL_EDGE_CHROMA] and
 * [ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA]. Every AVIF decode here carries it, and every HEIC decode on the
 * iOS simulator.
 *
 * A still decodes with the matrix and range its `colr nclx` names. A HEIC only does once its codestream states them
 * too, the way the overlay raster hands it over, since Apple's HEIC decoder takes the range from the HEVC codestream.
 * Without an `nclx` an AVIF decodes at full range and a HEIC in the range its codestream signals, limited when it
 * signals none, both with the BT.601 matrix. Every decode measured here is under a matrix core reads with Kr and Kb
 * alone, so the primaries and transfer the codestream or the `nclx` name play no part. A still whose matrix reads them,
 * 10, 12 or 13, or that core reads any other way, such as the identity matrix 0 or YCgCo 8, has no measured decode, and
 * asking for one fails with an [IllegalArgumentException].
 *
 * The overlay raster reads a HEIC or an AVIF from the bitmap ImageIO decodes it to, so each decode is in whole codes.
 * On macOS a HEIC's may be any whole codes within ImageIO's own drift of the unrounded ITU-T H.273 reading on each
 * channel, the most it was measured decoding one off that reading, and the unrounded reading rounded comes first, the
 * one [ycbcrToRgb] gives. Given the texel this process read, from [imageIoRead], those decodes narrow to the one it
 * lands on. An AVIF's, and on the iOS simulator a HEIC's, is the unrounded decode rounded, at either range. A HEIC
 * handed over as written decodes on both with its `nclx` matrix at the range its codestream signals.
 *
 * The macOS decodes were measured on macOS 26.6.2 (25G83) and the simulator's on the iOS 27.0 (24A434) and iOS 26.5
 * (23F77) simulators, each against the bitmap ImageIO hands back for every [ColorPictures] AVIF and HEIC alone and as
 * a grid, and every HEIC handed over as written. That covers both ranges, both matrices and a codestream that
 * disagrees with its `nclx` on matrix or on range. Another OS build may decode them elsewhere, which reads as a model
 * miss.
 *
 * No decode on an iPhone has been measured from ImageIO's bitmap, so every one there is unmeasured. One whose
 * codestream signals limited range under a full range `nclx` is unmeasured everywhere. Asking for an unmeasured decode
 * fails with [UnmeasuredDecode], and [missOrUnmeasured] turns that failure into one line of a test's misses.
 */
@InternalFilmstripApi
public object AppleStillSamples {
  /**
   * How far, in eight-bit codes, a sample Apple's decoder hands back sits from one [of] gives, on any channel.
   *
   * It covers rounding only, half a code.
   */
  public const val TOLERANCE: Float = 0.5f

  /**
   * The failure asking for a decode no one has measured on this platform raises, naming the still and the texel this
   * process read for it.
   */
  public class UnmeasuredDecode(
    message: String,
  ) : AssertionError(message)

  /**
   * The miss [check] reports for one still, or the [UnmeasuredDecode] message when its decode is unmeasured here.
   *
   * A test reading many stills runs each through this and fails once with every line, so one run on a platform reports
   * every still it read.
   */
  public inline fun missOrUnmeasured(check: () -> String?): String? =
    try {
      check()
    } catch (unmeasured: UnmeasuredDecode) {
      unmeasured.message
    }

  /**
   * HEICs painted like [ColorPictures.HEIC_NCLX_BT709_FULL] whose `colr nclx` names the BT.709 matrix 1 and the sRGB
   * transfer 13 at full range, one beside Display P3's primaries 12 and one beside the unspecified primaries 2.
   */
  public val BT709_MATRIX_OTHER_PRIMARIES: List<HeicPicture> =
    listOf(
      ColorPictures.HEIC_NCLX_DISPLAY_P3_PRIMARIES_BT709_MATRIX,
      HeicPicture(
        "HEIC nclx unspecified primaries, BT.709 matrix",
        CicpTag(UNSPECIFIED, UNSPECIFIED, BT709, true),
        profile = null,
        ColorPictures.HEIC_NCLX_BT709_FULL.ycbcr,
      ),
    )

  /**
   * A HEIC painted like [ColorPictures.HEIC_NCLX_BT709_FULL] whose `colr nclx` names the SMPTE 240M matrix 7 beside
   * BT.709 primaries and the sRGB transfer at full range. Apple's decoder does not read that matrix, so the overlay
   * raster restates the still to the matrix [decoderMatrix] gives and [landed] models its decode.
   */
  public val UNREAD_MATRIX: HeicPicture =
    HeicPicture(
      "HEIC nclx SMPTE 240M matrix",
      CicpTag(BT709, SRGB_TRANSFER, SMPTE_240M, true),
      profile = null,
      ColorPictures.HEIC_NCLX_BT709_FULL.ycbcr,
    )

  /**
   * Every texel Apple's decoder may hand back for [still] once its codestream and `colr nclx` state the matrix
   * [decoderMatrix] gives for its eight-bit 4:2:0 samples, as sixteen-bit red, green, blue and alpha. These are the
   * decodes [of] gives for the same still under that matrix.
   *
   * @param read The texel this process's decoder handed back for [still], named in the failure where its decode is
   * unmeasured.
   * @throws IllegalArgumentException for a still without an `nclx` or one [decoderMatrix] refuses.
   */
  public fun landed(
    still: HeicPicture,
    read: ShortArray? = null,
  ): List<ShortArray> = landed(still, read, ApplePlatform.current())

  internal fun landed(
    still: HeicPicture,
    read: ShortArray?,
    platform: ApplePlatform,
  ): List<ShortArray> {
    val nclx = requireNotNull(still.nclx) { "$still has no nclx to land" }
    val landed =
      requireNotNull(decoderMatrix(nclx, unsubsampled = false, EIGHT_BITS)) { "$still is refused before decoding" }
    val stated = CicpTag(nclx.primaries, nclx.transfer, landed.matrix, nclx.fullRange)
    return of(HeicPicture(still.name, stated, still.profile, still.ycbcr, still.vui, still.slice), read, platform)
  }

  /**
   * Every texel Apple's decoder may hand back for [still], as sixteen-bit red, green, blue and alpha.
   *
   * @param read The texel this process's decoder handed back for [still], named in the failure where its decode is
   * unmeasured.
   */
  public fun of(
    still: AvifPicture,
    read: ShortArray? = null,
  ): List<ShortArray> = of(still, read, ApplePlatform.current())

  internal fun of(
    still: AvifPicture,
    read: ShortArray?,
    platform: ApplePlatform,
  ): List<ShortArray> {
    if (platform == ApplePlatform.Iphone) unmeasured(still.name, read, platform)
    if (still.bitDepth > EIGHT_BITS) return twelveBit(checkNotNull(still.nclx), heic = false, read, platform)
    return listOf(roundedDecode(still.ycbcr, colorOf(still.nclx), still.nclx?.fullRange ?: true))
  }

  /**
   * Every texel Apple's decoder may hand back for a [AvifColorPictures.twelveBit] AVIF, or a
   * [HeicColorPictures.twelveBit] HEIC when [heic] is set, with [nclx] as its color box, once its codestream and
   * `nclx` state the matrix [decoderMatrix] gives, as sixteen-bit red, green, blue and alpha. It is empty where
   * [decoderMatrix] refuses the still, or where ImageIO decodes it and hands out none of its samples.
   *
   * ImageIO decodes either still at sixteen bits a sample, so the texel keeps the steps between eight-bit codes. It
   * lands within rounding of the whole codes [ycbcrToRgb] gives at twelve bits through the matrix [decoderMatrix]
   * gives, read here as those codes. Neither platform hands out a HEIC's samples at limited range, and drawing that
   * decode into a sixteen-bit bitmap draws nothing either. These were measured on macOS 26.6.2 (25G83) and the iOS 26.5
   * (23F77) simulator, against the bitmap ImageIO hands back for each still restated the way [decoderMatrix] says.
   *
   * @param read The texel this process's decoder handed back, named in the failure where its decode is unmeasured.
   * @throws IllegalArgumentException for a matrix outside [TWELVE_BIT_MATRICES].
   */
  public fun twelveBit(
    nclx: CicpTag,
    heic: Boolean,
    read: ShortArray? = null,
  ): List<ShortArray> = twelveBit(nclx, heic, read, ApplePlatform.current())

  internal fun twelveBit(
    nclx: CicpTag,
    heic: Boolean,
    read: ShortArray?,
    platform: ApplePlatform,
  ): List<ShortArray> {
    require(nclx.matrix in TWELVE_BIT_MATRICES) { "matrix ${nclx.matrix} has no twelve-bit decode measured on Apple" }
    if (platform == ApplePlatform.Iphone) unmeasured("The twelve-bit still with nclx $nclx", read, platform)
    val landed = decoderMatrix(nclx, unsubsampled = false, TWELVE_BITS) ?: return emptyList()
    if (heic && !nclx.fullRange) return emptyList()
    val (y, cb, cr) = AvifColorPictures.TWELVE_BIT_YCBCR
    val codes = ycbcrToRgb(y, cb, cr, landed.matrix, nclx.primaries, nclx.transfer, nclx.fullRange, TWELVE_BITS)
    return listOf(wholeCodeTexel(codes))
  }

  /**
   * The `nclx` matrices [twelveBit] has a decode measured for, every code point from 0 to 15.
   */
  public val TWELVE_BIT_MATRICES: List<Int> = (0..15).toList()

  /**
   * The matrix coefficients code points Apple's HEIF decoder reads as ITU-T H.273 says, the identity matrix 0 only at
   * full range on 4:4:4 samples at a depth in [EVALUATED_DEPTHS] and 2 for unspecified as BT.601. It takes a HEIC's
   * matrix from the `nclx`.
   */
  public val READABLE_MATRICES: Set<Int> = setOf(0, 1, 2, 5, 6, 9)

  /**
   * The bit depths of a 4:4:4 still whose stored samples ImageIO hands out under the identity matrix at full range,
   * measured at eight and ten bits on macOS 26.6.2 (25G83) and the iOS 26.5 (23F77) simulator.
   */
  public val EVALUATED_DEPTHS: Set<Int> = setOf(8, 10)

  /**
   * The matrix the overlay raster hands a still with [nclx] and [bitDepth]-bit samples to Apple's decoder through,
   * [nearestReadable] of [READABLE_MATRICES]. The identity matrix is left out unless the still is [unsubsampled] at
   * full range and a depth in [EVALUATED_DEPTHS]. It is null where the still is refused before it is decoded, for a
   * reading core refuses, the identity matrix on samples that are not 4:4:4 and a landing past
   * [NearestMatrix.withinBound].
   *
   * A still that is [unsubsampled] at a depth in [EVALUATED_DEPTHS] and does not land exactly is not decoded through
   * it, and draws at [evaluated] instead.
   */
  public fun decoderMatrix(
    nclx: CicpTag,
    unsubsampled: Boolean,
    bitDepth: Int,
  ): NearestMatrix? {
    if (nclx.matrix == IDENTITY && !unsubsampled) return null
    val evaluated = unsubsampled && bitDepth in EVALUATED_DEPTHS
    val readable = if (evaluated && nclx.fullRange) READABLE_MATRICES else READABLE_MATRICES - IDENTITY
    val nearest = ycbcrReading(nclx.matrix, nclx.primaries, nclx.transfer).nearestReadable(readable)
    return nearest?.takeIf { it.withinBound }
  }

  /**
   * The texel the overlay raster draws for a 4:4:4 still with [nclx] whose stored [bitDepth]-bit samples are [ycbcr],
   * as sixteen-bit red, green, blue and alpha, when [decoderMatrix] is not exact.
   *
   * The raster restates such a still to the identity matrix at full range, so ImageIO hands out its stored samples,
   * and decodes each through core's [evaluator]. That holds at eight and ten bits, measured on macOS 26.6.2 (25G83) and
   * the iOS 26.5 (23F77) simulator.
   */
  public fun evaluated(
    nclx: CicpTag,
    bitDepth: Int,
    ycbcr: List<Int>,
  ): ShortArray {
    val (y, cb, cr) = ycbcr
    val reading = ycbcrReading(nclx.matrix, nclx.primaries, nclx.transfer)
    val rgb = reading.evaluator(bitDepth, nclx.fullRange).rgb(y, cb, cr)
    return ShortArray(CHANNELS) { if (it < rgb.size) (rgb[it] * MAX_SAMPLE).roundToInt().toShort() else OPAQUE }
  }

  /**
   * Every texel Apple's decoder may hand back for [still] once its codestream states what its `colr nclx` names, as
   * sixteen-bit red, green, blue and alpha.
   *
   * @param read The texel this process's decoder handed back for [still]. On macOS the decodes narrow to the one it
   * lands on within [TOLERANCE], or stay whole when it lands on none. It is named in the failure where its decode is
   * unmeasured.
   */
  public fun of(
    still: HeicPicture,
    read: ShortArray? = null,
  ): List<ShortArray> = of(still, read, ApplePlatform.current())

  internal fun of(
    still: HeicPicture,
    read: ShortArray?,
    platform: ApplePlatform,
  ): List<ShortArray> = heicDecodes(still, heicDecode(still, asWritten = false), read, platform)

  /**
   * Every texel Apple's decoder may hand back for [still], an AVIF or a HEIC, as sixteen-bit red, green, blue and
   * alpha. These are the decodes the [AvifPicture] or [HeicPicture] overload gives for it.
   *
   * @param read The texel this process's decoder handed back for [still], named in the failure where its decode is
   * unmeasured.
   */
  public fun of(
    still: HeifPicture,
    read: ShortArray? = null,
  ): List<ShortArray> =
    when (still) {
      is AvifPicture -> of(still, read)
      is HeicPicture -> of(still, read)
    }

  /**
   * Every texel Apple's decoder may hand back for [still] handed over as written, with the matrix its `colr nclx`
   * names and the range its codestream signals, as sixteen-bit red, green, blue and alpha.
   *
   * These are the decodes [of] gives at the codestream's range, whatever matrix or primaries the codestream describes.
   *
   * Where the `nclx` names full range and the codestream signals limited range, the decode is unmeasured on every
   * platform whatever matrix either names. Asking for an unmeasured decode fails.
   *
   * @param read The texel this process's decoder handed back for [still], narrowing the decodes as [of] does. It is
   * named in the failure where its decode is unmeasured.
   */
  public fun asWritten(
    still: HeicPicture,
    read: ShortArray? = null,
  ): List<ShortArray> = asWritten(still, read, ApplePlatform.current())

  internal fun asWritten(
    still: HeicPicture,
    read: ShortArray?,
    platform: ApplePlatform,
  ): List<ShortArray> {
    val nclx = still.nclx
    val limitedUnderFullNclx = nclx != null && nclx.fullRange && still.vui.videoSignalType && !still.vui.fullRange
    if (limitedUnderFullNclx) unmeasured("$still handed over as written", read, platform)
    return heicDecodes(still, heicDecode(still, asWritten = true), read, platform)
  }

  /**
   * The sixteen-bit red, green and blue ImageIO decodes [bytes] to at their centre on this platform, the texel [of]
   * and [asWritten] narrow their decodes to.
   *
   * [bytes] are decoded as given, so a still is passed the way the overlay raster hands it to ImageIO.
   */
  public fun imageIoRead(bytes: ByteArray): ShortArray =
    imageIoCentre(bytes).map { (it * EIGHT_BIT_STEP).toShort() }.toShortArray()

  // The texel withinDrift starts from, the unrounded reading of still rounded on each channel.
  internal fun rounded(
    still: HeicPicture,
    asWritten: Boolean,
  ): ShortArray = roundedReading(heicDecode(still, asWritten))

  // The samples, color and range Apple's decoder decodes still with, handed over as written or with its codestream
  // stating what its nclx names. Without an nclx both take the codestream's range.
  internal fun heicDecode(
    still: HeicPicture,
    asWritten: Boolean,
  ): HeicDecode {
    val nclx = still.nclx
    val full = if (nclx == null || asWritten) still.vui.fullRange else nclx.fullRange
    return HeicDecode(still.ycbcr, colorOf(nclx), full)
  }

  private fun heicDecodes(
    still: HeicPicture,
    decode: HeicDecode,
    read: ShortArray?,
    platform: ApplePlatform,
  ): List<ShortArray> =
    when (platform) {
      ApplePlatform.MacOs -> withinDrift(decode).narrowedTo(read)
      ApplePlatform.Simulator -> listOf(roundedDecode(decode.ycbcr, decode.color, decode.full))
      ApplePlatform.Iphone -> unmeasured(still.name, read, platform)
    }

  // The red, green and blue codes from 0 to 255 core's evaluator reads decode's samples at, before ycbcrToRgb rounds
  // them.
  internal fun unroundedReading(decode: HeicDecode): List<Double> {
    val (y, cb, cr) = decode.ycbcr
    return decode.color.weights
      .evaluator(EIGHT_BITS, decode.full)
      .rgb(y, cb, cr)
      .map { it * MAX_CODE }
  }

  // Whole eight-bit red, green and blue codes as an opaque sixteen-bit texel.
  internal fun wholeCodeTexel(codes: IntArray): ShortArray =
    ShortArray(CHANNELS) { if (it < codes.size) (codes[it] * EIGHT_BIT_STEP).toShort() else OPAQUE }

  // Every whole code within WHOLE_CODE_DRIFT of the unrounded reading on each channel, starting from the reading
  // rounded, the texel ycbcrToRgb gives.
  private fun withinDrift(decode: HeicDecode): List<ShortArray> {
    val rounded = roundedReading(decode)
    val (reds, greens, blues) =
      unroundedReading(decode).map { code ->
        val low = ceil(code - WHOLE_CODE_DRIFT).toInt().coerceAtLeast(0)
        val high = floor(code + WHOLE_CODE_DRIFT).toInt().coerceAtMost(MAX_CODE)
        (low..high).toList()
      }
    val box =
      reds.flatMap { red ->
        greens.flatMap { green -> blues.map { blue -> wholeCodeTexel(intArrayOf(red, green, blue)) } }
      }
    return listOf(rounded) + box.filterNot { it.contentEquals(rounded) }
  }

  // The unrounded reading rounded to whole codes on each channel, inside 0 to 255 since core's evaluator clamps, as
  // ycbcrToRgb gives it.
  internal fun roundedReading(decode: HeicDecode): ShortArray {
    val codes = unroundedReading(decode).map { it.roundToInt() }
    return wholeCodeTexel(codes.toIntArray())
  }

  // The decodes read lands on within TOLERANCE on red, green and blue, or all of them when it lands on none, so a miss
  // still names every decode.
  private fun List<ShortArray>.narrowedTo(read: ShortArray?): List<ShortArray> {
    if (read == null) return this
    val landed =
      filter { decode ->
        (0 until RGB).all { abs(eightBitCode(decode[it]) - eightBitCode(read[it])) <= TOLERANCE }
      }
    return landed.ifEmpty { this }
  }

  private fun eightBitCode(sample: Short): Float = (sample.toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP.toFloat()

  private fun unmeasured(
    still: String,
    read: ShortArray?,
    platform: ApplePlatform,
  ): Nothing =
    throw UnmeasuredDecode(
      "$still decoded to ${read?.toList()} on ${platform.label}, where Apple's decode of it is unmeasured. Record " +
        "this beside the macOS and simulator decodes as this one's own",
    )

  // The unrounded decode rounded to whole eight-bit codes, the way ImageIO decodes an AVIF and the simulator's a HEIC.
  private fun roundedDecode(
    ycbcr: List<Int>,
    color: StillColor,
    full: Boolean,
  ): ShortArray =
    (reading(ycbcr, color, full).map { (it * MAX_CODE).roundToInt() / MAX_CODE.toDouble() } + 1.0)
      .map { (it * MAX_SAMPLE).roundToInt().toShort() }
      .toShortArray()

  // Red, green and blue from 0 to 1 through core's matrix for color, with full range chroma stretched from CoreVideo's
  // 254 codes onto the 255 core reads, which ycbcrToRgb's whole codes cannot give. Stretching both chroma samples about
  // their middle moves each channel away from the luma the Kr and Kb weights give by the same factor.
  private fun reading(
    ycbcr: List<Int>,
    color: StillColor,
    full: Boolean,
  ): List<Double> {
    val weights = color.weights
    val linear = checkNotNull(weights.rgbMatrix(EIGHT_BITS, full))
    val stretch = if (full) MAX_CODE / COREVIDEO_FULL_CHROMA else 1.0
    val (y, cb, cr) = ycbcr
    val (red, green, blue) = linear.rgb(y.toDouble(), cb.toDouble(), cr.toDouble()).toList()
    val luma = weights.kr * red + (1 - weights.kr - weights.kb) * green + weights.kb * blue
    return listOf(red, green, blue).map { (luma + (it - luma) * stretch).coerceIn(0.0, 1.0) }
  }

  // The matrix, primaries and transfer a still decodes with, BT.601 beside unspecified primaries and transfer when it
  // has no nclx. Only a matrix core reads with Kr and Kb alone has a measured decode.
  private fun colorOf(nclx: CicpTag?): StillColor {
    val matrix = nclx?.matrix ?: BT601
    val primaries = nclx?.primaries ?: UNSPECIFIED
    val transfer = nclx?.transfer ?: UNSPECIFIED
    val reading = ycbcrReading(matrix, primaries, transfer)
    val unmeasured = "Apple's decode under matrix $matrix is unmeasured, only matrices read with Kr and Kb alone are"
    require(matrix !in COLOR_READING_MATRICES) { unmeasured }
    require(reading is YcbcrReading.KrKb) { unmeasured }
    return StillColor(matrix, primaries, transfer, reading)
  }

  internal class HeicDecode(
    val ycbcr: List<Int>,
    val color: StillColor,
    val full: Boolean,
  )

  internal class StillColor(
    val matrix: Int,
    val primaries: Int,
    val transfer: Int,
    val weights: YcbcrReading.KrKb,
  )

  private const val BT601 = 6
  private const val EIGHT_BITS = 8
  private const val TWELVE_BITS = 12

  private const val IDENTITY = 0
  private const val BT709 = 1
  private const val UNSPECIFIED = 2
  private const val SRGB_TRANSFER = 13
  private const val SMPTE_240M = 7
  private const val CHANNELS = 4
  private const val RGB = 3
  internal const val MAX_CODE = 255
  private const val MAX_SAMPLE = 65535.0
  internal const val EIGHT_BIT_STEP = 257
  internal const val SAMPLE_MASK = 0xFFFF
  private const val OPAQUE: Short = -1

  // The matrices whose reading takes the primaries or the transfer, BT.2020 constant luminance and the two derived
  // from chromaticities.
  private val COLOR_READING_MATRICES = setOf(10, 12, 13)

  // CoreVideo's full range chroma runs 1 to 255.
  private const val COREVIDEO_FULL_CHROMA = 254.0

  /**
   * ImageIO's own limit on macOS, the most in eight-bit codes a whole code it decodes a HEIC to was measured off the
   * unrounded ITU-T H.273 reading on any channel, 1.39 codes.
   *
   * That was on macOS 26.6.2 (25G83), where the blue of [ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA] decoded to
   * 251 for a reading of 252.3832, 1.3832 codes off.
   */
  internal const val WHOLE_CODE_DRIFT: Double = 1.39
}
