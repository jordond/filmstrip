package dev.jordond.filmstrip.color

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Checks the shared SD primaries matrix against values worked by hand, and that a colour goes through it and back at
 * the middle of the range.
 */
class SdColorTest {
  @Test
  fun `the sd primaries are smpte c and ebu`() {
    SD_PRIMARIES shouldBe setOf(6, 5)
  }

  @Test
  fun `the matrices match their hand worked values`() {
    sdPrimariesToBt709(SMPTE_C_PRIMARIES) shouldMatch PRIMARIES_170M
    sdPrimariesToBt709(7) shouldMatch PRIMARIES_170M
    sdPrimariesToBt709(EBU_PRIMARIES) shouldMatch PRIMARIES_EBU
  }

  @Test
  fun `BT709 gets the identity`() {
    sdPrimariesToBt709(1) shouldMatch IDENTITY
  }

  @Test
  fun `the primaries matrix comes back through its inverse`() {
    val inverse = checkNotNull(invert(sdPrimariesToBt709(SMPTE_C_PRIMARIES)))
    for (color in MID_RANGE) {
      apply(inverse, apply(sdPrimariesToBt709(SMPTE_C_PRIMARIES), color)) shouldMatch color
    }
  }

  @Test
  fun `a mid range colour displays as worked by hand`() {
    sdSourceDisplayed(doubleArrayOf(0.6, 0.3, 0.2), SMPTE_C_PRIMARIES) shouldMatch
      doubleArrayOf(0.5858361309, 0.3064395579, 0.198244109)
    sdSourceDisplayed(doubleArrayOf(0.25, 0.55, 0.45), SMPTE_C_PRIMARIES) shouldMatch
      doubleArrayOf(0.2750884136, 0.5445985618, 0.4497679788)
    sdSourceDisplayed(doubleArrayOf(0.6, 0.3, 0.2), EBU_PRIMARIES) shouldMatch
      doubleArrayOf(0.6097861942, 0.3, 0.2014216221)
  }

  @Test
  fun `a colour on BT709 primaries displays as itself`() {
    for (color in MID_RANGE) sdSourceDisplayed(color, 1) shouldMatch color
  }

  @Test
  fun `a colour past BT709 clamps onto the range`() {
    sdSourceDisplayed(doubleArrayOf(0.0, 1.0, 0.0), 9)[0] shouldBe 0.0
  }

  @Test
  fun `what the matrices cannot read is refused`() {
    assertFailsWith<IllegalArgumentException> { sdPrimariesToBt709(2) }
    assertFailsWith<IllegalArgumentException> { sdPrimariesToBt709(4) }
    assertFailsWith<IllegalArgumentException> { sdSourceDisplayed(doubleArrayOf(0.5), SMPTE_C_PRIMARIES) }
  }

  private infix fun DoubleArray.shouldMatch(expected: DoubleArray) {
    size shouldBe expected.size
    forEachIndexed { index, value ->
      withClue("value $index of ${toList()}") { value shouldBe (expected[index] plusOrMinus MATRIX_TOLERANCE) }
    }
  }

  private companion object {
    const val MATRIX_TOLERANCE = 1e-9

    val MID_RANGE =
      listOf(
        doubleArrayOf(0.6, 0.3, 0.2),
        doubleArrayOf(0.25, 0.55, 0.45),
        doubleArrayOf(0.4, 0.4, 0.7),
        doubleArrayOf(0.5, 0.5, 0.5),
      )

    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    val PRIMARIES_170M =
      doubleArrayOf(
        0.9395420638,
        0.0501813569,
        0.0102765794,
        0.0177722231,
        0.9657928625,
        0.0164349144,
        -0.0016215999,
        -0.0043697497,
        1.0059913496,
      )

    val PRIMARIES_EBU =
      doubleArrayOf(
        1.0440432088,
        -0.0440432088,
        0.0,
        0.0,
        1.0,
        0.0,
        0.0,
        0.0117933783,
        0.9882066217,
      )
  }
}
