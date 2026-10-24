package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * An overlay picture tagged with an ICC profile, as the profile bytes and the one texel it is painted with.
 *
 * It carries no expected value.
 *
 * @property name What the profile is, for test messages.
 * @property profile The ICC profile's bytes, as a picture's `iCCP` chunk holds them once inflated.
 * @property texel The packed ARGB colour the picture is painted with, alpha included.
 */
@InternalFilmstripApi
public class IccPicture internal constructor(
  public val name: String,
  public val profile: ByteArray,
  public val texel: Int,
) {
  /**
   * A PNG of [size] painted [texel] all over and tagged with [profile], as [OverlayPixels.pngOf] writes it.
   */
  public fun pngOf(size: Size): ByteArray = OverlayPixels.pngOf(size, texel, profile)

  override fun toString(): String = name
}

/**
 * ICC tagged overlay pictures, one per kind of profile a picture can carry.
 *
 * Every profile is a version 4.3 profile with a D50 white and relative colorimetric as its intent, written out field by
 * field from the figures this object holds. Between them they cover a matrix/TRC profile with a parametric curve, an
 * identity curve and a sampled curve, a `mAB` lookup table profile and a grey profile.
 *
 * The RGB profiles are built from primaries with a D65 white, so each carries a `chad` tag holding the Bradford
 * adaptation from D65 to D50 their colorants went through.
 */
@InternalFilmstripApi
public object IccPictures {
  // The red, green and blue colorants of the published v4 sRGB and Display P3 profiles, already adapted to D50.
  private val SRGB_COLORANTS =
    listOf(
      doubleArrayOf(0.436066, 0.222488, 0.013916),
      doubleArrayOf(0.385147, 0.716873, 0.097076),
      doubleArrayOf(0.143066, 0.060608, 0.714096),
    )
  private val DISPLAY_P3_COLORANTS =
    listOf(
      doubleArrayOf(0.515121, 0.241196, -0.001053),
      doubleArrayOf(0.291977, 0.692245, 0.041885),
      doubleArrayOf(0.157104, 0.066574, 0.784073),
    )
  private val D50 = doubleArrayOf(0.9642, 1.0, 0.8249)

  // The Bradford adaptation of the D65 white to D50, row by row, that the colorants above went through.
  private val D65_TO_D50 =
    listOf(
      doubleArrayOf(1.047886, 0.022919, -0.050216),
      doubleArrayOf(0.029582, 0.990484, -0.017079),
      doubleArrayOf(-0.009252, 0.015073, 0.751678),
    )

  /**
   * sRGB primaries with the sRGB curve as a type 3 `para` curve, painted a mid green.
   */
  public val SRGB: IccPicture =
    IccPicture(
      name = "sRGB",
      profile = matrixProfile("sRGB", SRGB_COLORANTS, parametricSrgbCurve()),
      texel = AuthoredColours.MID_GREEN,
    )

  /**
   * sRGB primaries with linear light, an identity `curv`, painted a mid grey.
   */
  public val LINEAR_SRGB: IccPicture =
    IccPicture(
      name = "linear sRGB",
      profile = matrixProfile("Linear sRGB", SRGB_COLORANTS, curve(IntArray(0))),
      texel = AuthoredColours.MID_GREY,
    )

  /**
   * Display P3 primaries with the sRGB curve sampled into a 1024 entry `curv` table, painted an orange that sits
   * outside BT.709.
   */
  public val DISPLAY_P3: IccPicture =
    IccPicture(
      name = "Display P3",
      profile = matrixProfile("Display P3", DISPLAY_P3_COLORANTS, sampledSrgbCurve()),
      texel = 0xFFFF8000.toInt(),
    )

  /**
   * sRGB as a `mAB` lookup table, painted a colour whose channels all fall between the table's grid points.
   *
   * The A curves are the sRGB curve as a type 3 `para`. The three point CLUT holds the XYZ of linear sRGB at each
   * grid point, and the B curves are identities.
   */
  public val LUT_SRGB: IccPicture =
    IccPicture(
      name = "sRGB lookup table",
      profile = lutProfile(),
      texel = 0xFF40C080.toInt(),
    )

  /**
   * A grey profile whose `kTRC` is a single gamma `curv`, painted a mid grey.
   *
   * The gamma is stored as u8Fixed8, so the nearest it gets to 2.2 is 563/256, about 2.19922, and that is the gamma
   * the profile carries.
   */
  public val GREY: IccPicture =
    IccPicture(
      name = "grey",
      profile =
        profile(
          deviceClass = "mntr",
          colourSpace = "GRAY",
          description = "Grey gamma 563/256",
          tags = listOf("kTRC" to curve(intArrayOf(GAMMA_U8_FIXED8))),
        ),
      texel = AuthoredColours.MID_GREY,
    )

  /**
   * Every tagged picture.
   */
  public val ALL: List<IccPicture> = listOf(SRGB, LINEAR_SRGB, DISPLAY_P3, LUT_SRGB, GREY)

  private fun matrixProfile(
    description: String,
    colorants: List<DoubleArray>,
    curve: ByteArray,
  ): ByteArray =
    profile(
      deviceClass = "mntr",
      colourSpace = "RGB ",
      description = description,
      tags =
        listOf(
          "rXYZ" to xyz(colorants[0]),
          "gXYZ" to xyz(colorants[1]),
          "bXYZ" to xyz(colorants[2]),
          "rTRC" to curve,
          "gTRC" to curve,
          "bTRC" to curve,
          "chad" to s15Fixed16Array(D65_TO_D50),
        ),
    )

  private fun lutProfile(): ByteArray {
    val grid = (0 until LUT_GRID_POINTS).map { it.toDouble() / (LUT_GRID_POINTS - 1) }
    val (redXyz, greenXyz, blueXyz) = SRGB_COLORANTS
    var clut = ByteArray(CLUT_GRID_BYTES) { if (it < 3) LUT_GRID_POINTS.toByte() else 0 } + byteArrayOf(2, 0, 0, 0)
    for (red in grid) {
      for (green in grid) {
        for (blue in grid) {
          for (axis in 0 until 3) {
            val value = red * redXyz[axis] + green * greenXyz[axis] + blue * blueXyz[axis]
            clut += uInt16((value * PCS_XYZ_ONE).roundToInt())
          }
        }
      }
    }
    val bCurves = padded(curve(IntArray(0))).let { it + it + it }
    val aCurves = padded(parametricSrgbCurve()).let { it + it + it }
    val clutOffset = LUT_HEADER_BYTES + bCurves.size
    val aOffset = clutOffset + padded(clut).size
    val lut =
      "mAB ".encodeToByteArray() + ByteArray(4) + byteArrayOf(3, 3, 0, 0) +
        int32(LUT_HEADER_BYTES) + int32(0) + int32(0) + int32(clutOffset) + int32(aOffset) +
        bCurves + padded(clut) + aCurves

    return profile(
      deviceClass = "scnr",
      colourSpace = "RGB ",
      description = "sRGB lookup table",
      tags = listOf("A2B0" to lut, "chad" to s15Fixed16Array(D65_TO_D50)),
    )
  }

  private fun profile(
    deviceClass: String,
    colourSpace: String,
    description: String,
    tags: List<Pair<String, ByteArray>>,
  ): ByteArray {
    val allTags =
      listOf(
        "desc" to text(description),
        "cprt" to text("No copyright, use freely"),
        "wtpt" to xyz(D50),
      ) + tags
    var offset = HEADER_BYTES + 4 + TAG_ENTRY_BYTES * allTags.size
    var table = int32(allTags.size)
    var data = ByteArray(0)
    for ((signature, tag) in allTags) {
      table += signature.encodeToByteArray() + int32(offset) + int32(tag.size)
      data += padded(tag)
      offset += padded(tag).size
    }

    val header =
      int32(offset) +
        ByteArray(4) +
        byteArrayOf(4, 0x30, 0, 0) +
        deviceClass.encodeToByteArray() +
        colourSpace.encodeToByteArray() +
        "XYZ ".encodeToByteArray() +
        uInt16(2026) + uInt16(1) + uInt16(1) + ByteArray(6) +
        "acsp".encodeToByteArray() +
        ByteArray(24) +
        int32(RELATIVE_COLORIMETRIC) +
        D50.fold(ByteArray(0)) { bytes, value -> bytes + s15Fixed16(value) } +
        ByteArray(HEADER_BYTES - 80)
    return header + table + data
  }

  private fun parametricSrgbCurve(): ByteArray =
    "para".encodeToByteArray() + ByteArray(4) + uInt16(3) + ByteArray(2) +
      doubleArrayOf(SRGB_GAMMA, 1 / SRGB_SCALE, SRGB_OFFSET / SRGB_SCALE, 1 / SRGB_SLOPE, SRGB_KNEE)
        .fold(ByteArray(0)) { bytes, value -> bytes + s15Fixed16(value) }

  private fun sampledSrgbCurve(): ByteArray =
    curve(
      IntArray(SAMPLED_CURVE_ENTRIES) {
        val code = it.toDouble() / (SAMPLED_CURVE_ENTRIES - 1)
        val linear = if (code < SRGB_KNEE) code / SRGB_SLOPE else ((code + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_GAMMA)
        (linear * UINT16_MAX).roundToInt()
      },
    )

  private fun curve(entries: IntArray): ByteArray =
    "curv".encodeToByteArray() + ByteArray(4) + int32(entries.size) +
      entries.fold(ByteArray(0)) { bytes, entry -> bytes + uInt16(entry) }

  private fun xyz(values: DoubleArray): ByteArray =
    "XYZ ".encodeToByteArray() + ByteArray(4) + values.fold(ByteArray(0)) { bytes, value -> bytes + s15Fixed16(value) }

  private fun s15Fixed16Array(rows: List<DoubleArray>): ByteArray =
    "sf32".encodeToByteArray() + ByteArray(4) +
      rows.fold(ByteArray(0)) { bytes, row -> row.fold(bytes) { rowBytes, value -> rowBytes + s15Fixed16(value) } }

  private fun text(value: String): ByteArray {
    val utf16 = value.fold(ByteArray(0)) { bytes, char -> bytes + uInt16(char.code) }
    return "mluc".encodeToByteArray() + ByteArray(4) + int32(1) + int32(MLUC_RECORD_BYTES) +
      "enUS".encodeToByteArray() + int32(utf16.size) + int32(MLUC_TEXT_OFFSET) + utf16
  }

  private fun padded(bytes: ByteArray): ByteArray = bytes + ByteArray((4 - bytes.size % 4) % 4)

  private fun s15Fixed16(value: Double): ByteArray = int32((value * FIXED_ONE).roundToInt())

  private fun int32(value: Int): ByteArray =
    byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

  private fun uInt16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

  private const val SRGB_GAMMA = 2.4
  private const val SRGB_SCALE = 1.055
  private const val SRGB_OFFSET = 0.055
  private const val SRGB_SLOPE = 12.92
  private const val SRGB_KNEE = 0.04045

  // The u8Fixed8 nearest 2.2 a single entry curv holds, 563/256.
  private const val GAMMA_U8_FIXED8 = 0x0233

  private const val SAMPLED_CURVE_ENTRIES = 1024
  private const val LUT_GRID_POINTS = 3
  private const val LUT_HEADER_BYTES = 32
  private const val CLUT_GRID_BYTES = 16

  // A 16-bit PCS XYZ value of 1.0, per the v4 encoding.
  private const val PCS_XYZ_ONE = 32768.0
  private const val UINT16_MAX = 65535.0
  private const val FIXED_ONE = 65536.0
  private const val RELATIVE_COLORIMETRIC = 1
  private const val HEADER_BYTES = 128
  private const val TAG_ENTRY_BYTES = 12
  private const val MLUC_RECORD_BYTES = 12
  private const val MLUC_TEXT_OFFSET = 28
}
