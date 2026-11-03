package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.linearToSrgb
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.pictureColorFromDecodedProfile
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.awt.Point
import java.awt.color.ColorSpace
import java.awt.color.ICC_ColorSpace
import java.awt.color.ICC_Profile
import java.awt.color.ICC_ProfileGray
import java.awt.color.ICC_ProfileRGB
import java.awt.image.BandedSampleModel
import java.awt.image.ColorConvertOp
import java.awt.image.DataBuffer
import java.awt.image.Raster
import java.awt.image.WritableRaster
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * The tagged pictures read by the JDK, as an independent check that their bytes mean what they were built to.
 *
 * Every profile is parsed by [ICC_Profile] and converted to D50 XYZ by [ColorConvertOp] over a grid of 17 steps
 * per channel. The XYZ each one was built to give is worked out here from the published primaries, the D65 white
 * adapted to D50 with Bradford and each profile's own curve, and every RGB profile's `chad` tag is read back against
 * that same Bradford adaptation. Every PNG is decoded by ImageIO back to its texel and its profile.
 *
 * The same grid checks [pictureLight] and [pictureSrgbCodes] on every profile, against the JDK's XYZ adapted to D65
 * with Bradford and taken to linear BT.2020 and BT.709.
 */
class IccPicturesJvmTest {
  @Test
  fun sRgbGivesTheXyzOfSrgb() {
    assertXyz(IccPictures.SRGB) { rgb -> SRGB_TO_XYZ * rgb.map(::srgbToLinear) }
  }

  @Test
  fun linearSrgbGivesTheXyzOfSrgbWithoutItsCurve() {
    assertXyz(IccPictures.LINEAR_SRGB) { rgb -> SRGB_TO_XYZ * rgb }
  }

  @Test
  fun displayP3GivesTheXyzOfP3PrimariesThroughTheSrgbCurve() {
    assertXyz(IccPictures.DISPLAY_P3) { rgb -> P3_TO_XYZ * rgb.map(::srgbToLinear) }
  }

  @Test
  fun displayP3CarriesRedsNegativeZ() {
    val colorants = (ICC_Profile.getInstance(IccPictures.DISPLAY_P3.profile) as ICC_ProfileRGB).matrix
    val off = (0 until 3).maxOf { row -> (0 until 3).maxOf { abs(colorants[row][it] - P3_TO_XYZ[row][it]) } }

    withClue("Red's Z is ${colorants[2][0]}") { (colorants[2][0] < 0f) shouldBe true }
    withClue("The colorants are off by $off") { (off <= TOLERANCE) shouldBe true }
  }

  @Test
  fun theLookupTableGivesTheXyzOfSrgb() {
    assertXyz(IccPictures.LUT_SRGB) { rgb -> SRGB_TO_XYZ * rgb.map(::srgbToLinear) }
  }

  @Test
  fun greyGivesTheD50WhiteScaledByItsGamma() {
    val gamma = (ICC_Profile.getInstance(IccPictures.GREY.profile) as ICC_ProfileGray).gamma

    gamma shouldBe GREY_GAMMA.toFloat()
    assertXyz(IccPictures.GREY) { grey -> D50.map { it * grey[0].pow(GREY_GAMMA) } }
  }

  @Test
  fun everyRgbProfileCarriesTheBradfordAdaptationFromD65ToD50() {
    for (picture in IccPictures.ALL - IccPictures.GREY) {
      val chad = ICC_Profile.getInstance(picture.profile).getData(ICC_Profile.icSigChromaticAdaptationTag)
      withClue("$picture has no chad tag") { (chad != null) shouldBe true }
      val read = ByteBuffer.wrap(chad)
      withClue("$picture chad type") { String(chad, 0, 4, Charsets.US_ASCII) shouldBe "sf32" }
      withClue("$picture chad size") { chad.size shouldBe SF32_HEADER_BYTES + 9 * 4 }

      for (row in 0 until 3) {
        for (column in 0 until 3) {
          val value = read.getInt(SF32_HEADER_BYTES + (row * 3 + column) * 4) / 65536.0
          val off = abs(value - BRADFORD_D65_TO_D50[row][column])
          withClue("$picture chad[$row][$column] is $value, off by $off") { (off <= CHAD_TOLERANCE) shouldBe true }
        }
      }
    }
  }

  @Test
  fun imageIoDecodesEveryPictureToItsTexelAndProfile() {
    for (picture in IccPictures.ALL) {
      val reader = ImageIO.getImageReadersByFormatName("png").next()
      reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(picture.pngOf(LARGE)))
      val raster = reader.read(0).raster
      val tree = reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0") as IIOMetadataNode
      val iccp = tree.getElementsByTagName("iCCP").item(0) as IIOMetadataNode
      val texel = picture.texel
      val expected =
        if (raster.numBands == 2) {
          intArrayOf(texel and 0xFF, texel ushr 24)
        } else {
          intArrayOf(texel shr 16 and 0xFF, texel shr 8 and 0xFF, texel and 0xFF, texel ushr 24)
        }

      withClue("$picture") { raster.getPixel(LARGE.width - 1, LARGE.height - 1, null as IntArray?) shouldBe expected }
      withClue("$picture profile") { inflate(iccp.userObject as ByteArray) shouldBe picture.profile }
    }
  }

  @Test
  fun pictureLightAgreesWithTheJdkOnEveryProfile() {
    for (picture in IccPictures.ALL) {
      val (codes, xyz) = jdkGrid(picture)
      val light = pictureLight(colorOf(picture), argbOf(codes))

      codes.indices.forEach { index ->
        val expected = PCS_TO_BT2020 * xyz[index]
        for (channel in 0 until 3) {
          val read = light[index * 4 + channel] / HDR_REFERENCE_WHITE_NITS.toDouble()
          val off = abs(read - expected[channel].coerceIn(0.0, 1.0))
          withClue("$picture at ${codes[index].toList()} channel $channel is off by $off") {
            (off <= TOLERANCE) shouldBe true
          }
        }
      }
    }
  }

  @Test
  fun pictureSrgbCodesAgreeWithTheJdkOnEveryProfile() {
    for (picture in IccPictures.ALL) {
      val (codes, xyz) = jdkGrid(picture)
      val srgb = pictureSrgbCodes(colorOf(picture), argbOf(codes))

      codes.indices.forEach { index ->
        val expected = PCS_TO_BT709 * xyz[index]
        for (channel in 0 until 3) {
          // The JDK's light is known to within the tolerance, so any code that range rounds to is right.
          val lowest = srgbCode(expected[channel] - TOLERANCE)
          val highest = srgbCode(expected[channel] + TOLERANCE)
          val code = srgb[index] shr (16 - channel * 8) and 0xFF
          withClue("$picture at ${codes[index].toList()} channel $channel is $code") {
            (code in lowest..highest) shouldBe true
          }
        }
      }
    }
  }

  private fun colorOf(picture: IccPicture): PictureColor {
    val channels = if (picture === IccPictures.GREY) 1 else 3
    return withClue("$picture profile") { pictureColorFromDecodedProfile(picture.profile, channels).shouldNotBeNull() }
  }

  // Every stored code on a grid of 17 steps per channel, or 17 greys for a grey picture, and the D50 XYZ the JDK
  // reads each one as.
  private fun jdkGrid(picture: IccPicture): Pair<List<IntArray>, List<List<Double>>> {
    val source = ICC_ColorSpace(ICC_Profile.getInstance(picture.profile))
    val channels = source.numComponents
    val steps = (0 until STEPS).map { (it * 255.0 / (STEPS - 1)).roundToInt() }
    val codes =
      if (channels == 1) {
        steps.map { intArrayOf(it, it, it) }
      } else {
        steps.flatMap { r -> steps.flatMap { g -> steps.map { b -> intArrayOf(r, g, b) } } }
      }
    val input = floatRaster(codes.size, channels)
    codes.forEachIndexed { x, code -> input.setPixel(x, 0, FloatArray(channels) { code[it] / 255f }) }
    val output = floatRaster(codes.size, 3)

    ColorConvertOp(source, ColorSpace.getInstance(ColorSpace.CS_CIEXYZ), null).filter(input, output)

    return codes to codes.indices.map { x -> output.getPixel(x, 0, null as FloatArray?).map { it.toDouble() } }
  }

  private fun argbOf(codes: List<IntArray>): IntArray =
    codes.map { (0xFF shl 24) or (it[0] shl 16) or (it[1] shl 8) or it[2] }.toIntArray()

  private fun srgbCode(light: Double): Int = (linearToSrgb(light.coerceIn(0.0, 1.0).toFloat()) * 255).roundToInt()

  private fun assertXyz(
    picture: IccPicture,
    intended: (List<Double>) -> List<Double>,
  ) {
    val source = ICC_ColorSpace(ICC_Profile.getInstance(picture.profile))
    val channels = source.numComponents
    val samples =
      (0 until STEPS.toDouble().pow(channels).toInt()).map { index ->
        List(channels) { channel -> stepOf(index, channel).toDouble() / (STEPS - 1) }
      }
    val input = floatRaster(samples.size, channels)
    samples.forEachIndexed { x, sample -> input.setPixel(x, 0, sample.map { it.toFloat() }.toFloatArray()) }
    val output = floatRaster(samples.size, 3)

    ColorConvertOp(source, ColorSpace.getInstance(ColorSpace.CS_CIEXYZ), null).filter(input, output)

    samples.forEachIndexed { x, sample ->
      val read = output.getPixel(x, 0, null as FloatArray?)
      // The JDK's XYZ space holds nothing below zero, so P3 red's slightly negative Z reads as zero there.
      val built = intended(sample).map { it.coerceAtLeast(0.0) }
      val off = (0 until 3).maxOf { abs(read[it] - built[it]) }
      withClue("$picture at $sample read ${read.toList()}, built for $built, off by $off") {
        (off <= TOLERANCE) shouldBe true
      }
    }
  }

  private fun stepOf(
    index: Int,
    channel: Int,
  ): Int = index / STEPS.toDouble().pow(channel).toInt() % STEPS

  private fun floatRaster(
    width: Int,
    bands: Int,
  ): WritableRaster = Raster.createWritableRaster(BandedSampleModel(DataBuffer.TYPE_FLOAT, width, 1, bands), Point())

  private fun inflate(bytes: ByteArray): ByteArray {
    val inflater = Inflater().apply { setInput(bytes) }
    val buffer = ByteArray(bytes.size * 2 + 1024)
    val length = inflater.inflate(buffer)
    withClue("The profile inflates in one pass") { inflater.finished() shouldBe true }
    inflater.end()
    return buffer.copyOf(length)
  }

  private fun srgbToLinear(code: Double): Double =
    if (code <= 0.04045) code / 12.92 else ((code + 0.055) / 1.055).pow(2.4)

  private operator fun List<List<Double>>.times(rgb: List<Double>): List<Double> =
    map { row -> row.indices.sumOf { row[it] * rgb[it] } }

  private companion object {
    // A grid of 17 steps per channel, so the middle of every curve and of the lookup table's cells is read.
    const val STEPS = 17
    const val TOLERANCE = 1.0 / 1000

    // A chad entry is s15Fixed16, which steps by about 1.5e-5, so this holds half a step of rounding and little more.
    const val CHAD_TOLERANCE = 1.0 / 100_000

    // The u8Fixed8 nearest 2.2 the grey profile's curv stores.
    const val GREY_GAMMA = 563.0 / 256

    // Wider than one stored deflate block holds, so the decoder joins several.
    val LARGE = Size(200, 100)

    val D50 = listOf(0.9642, 1.0, 0.8249)
    val D65 = listOf(0.3127, 0.3290)
    val SRGB_PRIMARIES = listOf(0.64 to 0.33, 0.30 to 0.60, 0.15 to 0.06)
    val P3_PRIMARIES = listOf(0.680 to 0.320, 0.265 to 0.690, 0.150 to 0.060)
    val BRADFORD =
      listOf(
        listOf(0.8951, 0.2664, -0.1614),
        listOf(-0.7502, 1.7135, 0.0367),
        listOf(0.0389, -0.0685, 1.0296),
      )

    const val SF32_HEADER_BYTES = 8

    val BT709_PRIMARIES = SRGB_PRIMARIES
    val BT2020_PRIMARIES = listOf(0.708 to 0.292, 0.170 to 0.797, 0.131 to 0.046)

    val BRADFORD_D65_TO_D50 = bradford(xyz(D65[0], D65[1]), D50)
    val SRGB_TO_XYZ = toD50Xyz(SRGB_PRIMARIES)
    val P3_TO_XYZ = toD50Xyz(P3_PRIMARIES)

    // D50 XYZ adapted to D65 with Bradford, then to each output's linear primaries.
    val PCS_TO_BT2020 = product(inverse(toD65Xyz(BT2020_PRIMARIES)), bradford(D50, xyz(D65[0], D65[1])))
    val PCS_TO_BT709 = product(inverse(toD65Xyz(BT709_PRIMARIES)), bradford(D50, xyz(D65[0], D65[1])))

    fun xyz(
      x: Double,
      y: Double,
    ) = listOf(x / y, 1.0, (1 - x - y) / y)

    // The primaries' matrix to XYZ for a D65 white.
    fun toD65Xyz(primaries: List<Pair<Double, Double>>): List<List<Double>> {
      val columns = primaries.map { (x, y) -> xyz(x, y) }
      val primariesMatrix = List(3) { row -> List(3) { column -> columns[column][row] } }
      val scale = solve(primariesMatrix, xyz(D65[0], D65[1]))
      return List(3) { row -> List(3) { column -> primariesMatrix[row][column] * scale[column] } }
    }

    // The primaries' matrix to XYZ for a D65 white, then the Bradford adaptation of that white to D50.
    fun toD50Xyz(primaries: List<Pair<Double, Double>>): List<List<Double>> =
      product(BRADFORD_D65_TO_D50, toD65Xyz(primaries))

    fun bradford(
      from: List<Double>,
      to: List<Double>,
    ): List<List<Double>> {
      val sourceCone = multiply(BRADFORD, from)
      val targetCone = multiply(BRADFORD, to)
      val coneScale =
        List(3) { row -> List(3) { column -> if (row == column) targetCone[row] / sourceCone[row] else 0.0 } }
      return product(inverse(BRADFORD), product(coneScale, BRADFORD))
    }

    fun multiply(
      matrix: List<List<Double>>,
      vector: List<Double>,
    ): List<Double> = matrix.map { row -> row.indices.sumOf { row[it] * vector[it] } }

    fun product(
      left: List<List<Double>>,
      right: List<List<Double>>,
    ): List<List<Double>> =
      List(3) { row -> List(3) { column -> (0 until 3).sumOf { left[row][it] * right[it][column] } } }

    fun solve(
      matrix: List<List<Double>>,
      vector: List<Double>,
    ): List<Double> = multiply(inverse(matrix), vector)

    fun inverse(m: List<List<Double>>): List<List<Double>> {
      fun at(
        row: Int,
        column: Int,
      ) = m[row % 3][column % 3]
      val cofactors =
        List(3) { row ->
          List(3) { column ->
            at(row + 1, column + 1) * at(row + 2, column + 2) - at(row + 1, column + 2) * at(row + 2, column + 1)
          }
        }
      val determinant = (0 until 3).sumOf { m[0][it] * cofactors[0][it] }
      return List(3) { row -> List(3) { column -> cofactors[column][row] / determinant } }
    }
  }
}
