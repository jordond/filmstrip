package dev.jordond.filmstrip.media

import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.ColorConvertOp
import java.awt.image.DataBuffer
import java.awt.image.Raster
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

// The JDK's own colour management, LCMS, is the independent reference here. Each profile goes through it to D50 XYZ,
// then through the same Bradford adaptation and BT.2020 matrix pictureLight uses, and the two must agree.
class PictureLightJvmTest {
  @Test
  fun `the reference reads D50 white as D50`() {
    val white = jdkPcs(TestIccProfiles.srgb, grey = false, listOf(intArrayOf(255, 255, 255)))[0]

    white.forEachIndexed { axis, value ->
      assertTrue(abs(value - PCS_WHITE[axis]) <= TOLERANCE, "white axis $axis read $value")
    }
  }

  @Test
  fun `every profile agrees with the JDK on a seventeen step grid`() {
    PROFILES.forEach { (name, profile) ->
      val (inputs, reference) = jdkGrid(name, profile)
      val light = pictureLight(Icc(profile), argbOf(inputs))

      var worst = 0.0
      inputs.indices.forEach { index ->
        val expected = linear(PCS_TO_BT2020, reference[index])
        for (row in 0 until 3) {
          worst = maxOf(worst, abs(expected[row] - light[index * 4 + row] / HDR_REFERENCE_WHITE_NITS.toDouble()))
        }
      }
      assertTrue(worst <= TOLERANCE, "$name parted from the JDK by $worst")
    }
  }

  @Test
  fun `display p3 code points agree with the JDK reading a display p3 profile`() {
    val (inputs, reference) = jdkGrid("Display P3", TestIccProfiles.displayP3)
    val light = pictureLight(Cicp(12, 13, 0, true), argbOf(inputs))

    var worst = 0.0
    inputs.indices.forEach { index ->
      val expected = linear(PCS_TO_BT2020, reference[index])
      for (row in 0 until 3) {
        worst = maxOf(worst, abs(expected[row] - light[index * 4 + row] / HDR_REFERENCE_WHITE_NITS.toDouble()))
      }
    }
    assertTrue(worst <= TOLERANCE, "Display P3 code points parted from the JDK by $worst")
  }

  @Test
  fun `every profile lands on the sRGB codes of the JDK's BT 709 light`() {
    PROFILES.forEach { (name, profile) ->
      val (inputs, reference) = jdkGrid(name, profile)
      val codes = pictureSrgbCodes(Icc(profile), argbOf(inputs))

      inputs.indices.forEach { index ->
        val expected = linear(PCS_TO_BT709, reference[index])
        for (row in 0 until 3) {
          // The JDK's light is known to within the tolerance, so any code that range rounds to is right.
          val lowest = srgbCode(expected[row] - TOLERANCE)
          val highest = srgbCode(expected[row] + TOLERANCE)
          val code = codes[index] shr (16 - row * 8) and 0xFF
          assertTrue(code in lowest..highest, "$name ${inputs[index].toList()} channel $row is $code")
        }
      }
    }
  }

  private fun jdkGrid(
    name: String,
    profile: ByteArray,
  ): Pair<List<IntArray>, List<DoubleArray>> {
    val steps = (0 until GRID).map { (it * 255.0 / (GRID - 1)).roundToInt() }
    val grey = name == "grey"
    val inputs =
      if (grey) {
        steps.map { intArrayOf(it, it, it) }
      } else {
        steps.flatMap { r -> steps.flatMap { g -> steps.map { b -> intArrayOf(r, g, b) } } }
      }
    return inputs to jdkPcs(profile, grey, inputs)
  }

  private fun argbOf(inputs: List<IntArray>): IntArray =
    inputs.map { (0xFF shl 24) or (it[0] shl 16) or (it[1] shl 8) or it[2] }.toIntArray()

  private fun linear(
    pcsToRgb: DoubleArray,
    pcs: DoubleArray,
  ): DoubleArray = DoubleArray(3) { row -> (0 until 3).sumOf { pcsToRgb[row * 3 + it] * pcs[it] }.coerceIn(0.0, 1.0) }

  private fun srgbCode(light: Double): Int = (linearToSrgb(light.coerceIn(0.0, 1.0).toFloat()) * 255).roundToInt()

  private fun jdkPcs(
    profile: ByteArray,
    grey: Boolean,
    codes: List<IntArray>,
  ): List<DoubleArray> {
    val bands = if (grey) 1 else 3
    val source = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, codes.size, 1, bands, null)
    codes.forEachIndexed { x, code -> for (band in 0 until bands) source.setSample(x, 0, band, code[band] * 257) }
    val target = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, codes.size, 1, 3, null)
    val xyz = ICC_Profile.getInstance(ColorSpace.CS_CIEXYZ)
    ColorConvertOp(arrayOf(ICC_Profile.getInstance(profile), xyz), null).filter(source, target)
    return codes.indices.map { x -> DoubleArray(3) { target.getSample(x, 0, it) / XYZ_STEPS_PER_ONE } }
  }

  private companion object {
    const val GRID = 17
    const val TOLERANCE = 1e-3

    // A sixteen-bit XYZ sample is u1Fixed15, so one is 32768.
    const val XYZ_STEPS_PER_ONE = 32768.0

    val PROFILES =
      listOf(
        "sRGB" to TestIccProfiles.srgb,
        "sRGB table" to TestIccProfiles.srgbTable,
        "gamma 2.2" to TestIccProfiles.gamma22,
        "nudged sRGB" to TestIccProfiles.nudgedSrgb,
        "linear sRGB" to TestIccProfiles.linearSrgb,
        "Display P3" to TestIccProfiles.displayP3,
        "Bradford sRGB" to TestIccProfiles.bradfordSrgb,
        "sparse sRGB table" to TestIccProfiles.sparseSrgbTable,
        "mAB" to TestIccProfiles.mab,
        "grey" to TestIccProfiles.grey22,
      )
  }
}
