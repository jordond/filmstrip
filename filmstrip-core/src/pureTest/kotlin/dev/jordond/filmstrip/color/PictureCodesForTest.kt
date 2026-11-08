package dev.jordond.filmstrip.color

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.sqrt
import kotlin.test.Test

class PictureCodesForTest {
  @Test
  fun `an untagged or sRGB picture stores the fill's own codes`() {
    for (color in listOf(null, Cicp(1, 13, 6, true), Icc(TestIccProfiles.srgb))) {
      withClue("$color") { pictureCodesFor(FILL[0], FILL[1], FILL[2], color).toList() shouldBe FILL.toList() }
    }
  }

  @Test
  fun `a tagged picture stores the codes that light up as the fill within one code`() {
    // The lookup table profile's gamut is a narrow one of its own, so it takes a fill nearer grey that it holds.
    val colors =
      listOf(
        Triple("Display P3 nclx", Cicp(12, 13, 6, true), FILL),
        Triple("BT.2020 PQ nclx", Cicp(9, 16, 9, false), FILL),
        Triple("BT.2020 HLG nclx", Cicp(9, 18, 9, false), FILL),
        Triple("matrix and curves P3 profile", Icc(TestIccProfiles.displayP3), FILL),
        Triple("mAB lookup table profile", Icc(TestIccProfiles.mab), PALE_FILL),
      )
    for ((name, color, fill) in colors) {
      withClue(name) {
        val codes = pictureCodesFor(fill[0], fill[1], fill[2], color)
        codes.toList() shouldNotBe fill.toList()
        val inside = codes.none { it == 0 || it == 0xFFFF }
        withClue("${codes.toList()} should sit inside the range") { inside.shouldBeTrue() }
        shouldBeWithinOneCode(color, codes, light(null, fill))
      }
    }
  }

  @Test
  fun `a fill a narrower gamut cannot hold clamps to the nearest code`() {
    val narrow = Icc(TestIccProfiles.matrixTrc(TestIccProfiles.d50Colorants(NARROW), TestIccProfiles.gammaCurv(2.2)))
    val red = intArrayOf(0xFFFF, 0x1000, 0x0800)
    val codes = pictureCodesFor(red[0], red[1], red[2], narrow)
    withClue("${codes.toList()} should stop at an end of the range") {
      codes.any { it == 0 || it == 0xFFFF }.shouldBeTrue()
    }
    shouldBeNearest(narrow, codes, light(null, red))
  }

  // The fill's light is closer to [codes]' light than to any code one step away, and no further from it than one step
  // moves the light.
  private fun shouldBeWithinOneCode(
    color: PictureColor,
    codes: IntArray,
    wanted: DoubleArray,
  ) {
    val here = light(color, codes)
    val step = neighbours(codes).maxOf { distance(light(color, it), here) }
    withClue("${codes.toList()} lands ${distance(here, wanted)} from the fill, one code moves it $step") {
      (distance(here, wanted) <= step).shouldBeTrue()
    }
    shouldBeNearest(color, codes, wanted)
  }

  private fun shouldBeNearest(
    color: PictureColor,
    codes: IntArray,
    wanted: DoubleArray,
  ) {
    val miss = distance(light(color, codes), wanted)
    for (neighbour in neighbours(codes)) {
      withClue("${neighbour.toList()} lands nearer than ${codes.toList()}") {
        (distance(light(color, neighbour), wanted) >= miss).shouldBeTrue()
      }
    }
  }

  private fun neighbours(codes: IntArray): List<IntArray> =
    (0 until 3).flatMap { channel ->
      listOf(1, -1).mapNotNull { step ->
        (codes[channel] + step).takeIf { it in 0..0xFFFF }?.let { next -> codes.copyOf().also { it[channel] = next } }
      }
    }

  private fun light(
    color: PictureColor?,
    codes: IntArray,
  ): DoubleArray {
    val nits = pictureLight(color, ShortArray(4) { if (it < 3) codes[it].toShort() else -1 })
    return DoubleArray(3) { nits[it].toDouble() }
  }

  private fun distance(
    a: DoubleArray,
    b: DoubleArray,
  ): Double = sqrt((0 until 3).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })

  private companion object {
    // Mid saturation, neither a primary nor a grey.
    val FILL = intArrayOf(0xC000, 0x7000, 0x5000)
    val PALE_FILL = intArrayOf(0x9000, 0x8000, 0x7000)
    val NARROW = doubleArrayOf(0.55, 0.33, 0.32, 0.50, 0.20, 0.15)
  }
}
