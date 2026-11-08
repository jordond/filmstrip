package dev.jordond.filmstrip.effects.filters.lut

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CubeLutTest {
  // A curve on red and a cross-talk of blue into green, so no read here is linear in its input.
  private val curved =
    cubeText(3) { red, green, blue ->
      floatArrayOf(red * red, green * 0.5f + blue * blue * 0.5f, 1f - blue)
    }

  @Test
  fun parseReadsCommentsTitleAndRowsInCubeOrder() {
    val table =
      CubeLut.parse(
        """
        # graded on a Tuesday
        TITLE "Tuesday"

        LUT_3D_SIZE 2
        DOMAIN_MIN 0.0 0.0 0.0
        DOMAIN_MAX 1.0 1.0 1.0
        0 0 0
        0.5 0 0
        0 0.5 0
        0.5 0.5 0
        0 0 0.5
        0.5 0 0.5
        0 0.5 0.5
        0.5 0.5 0.5
        """.trimIndent(),
      )

    table.size shouldBe 2
    table.at(1f, 0f, 0f).toList() shouldBe listOf(0.5f, 0f, 0f)
    table.at(0f, 1f, 0f).toList() shouldBe listOf(0f, 0.5f, 0f)
    table.at(0f, 0f, 1f).toList() shouldBe listOf(0f, 0f, 0.5f)
  }

  @Test
  fun aByteOrderMarkAheadOfTheFileIsSkipped() {
    CubeLut.parse("\uFEFF" + curved).size shouldBe 3
  }

  @Test
  fun aCrlfFileReadsLikeAnLfOne() {
    CubeLut.parse(curved.replace("\n", "\r\n")) shouldBe CubeLut.parse(curved)
  }

  @Test
  fun aLoneCrFileReadsLikeAnLfOne() {
    CubeLut.parse(curved.replace("\n", "\r")) shouldBe CubeLut.parse(curved)
  }

  @Test
  fun aBadLineIsNumberedTheSameUnderEveryLineEnd() {
    val bad = "# a comment\nnot a row\n"

    listOf("\n", "\r\n", "\r").forEach { ending ->
      assertFailsWith<IllegalArgumentException> {
        CubeLut.parse(bad.replace("\n", ending))
      }.message.shouldNotBeNull() shouldContain "Line 2"
    }
  }

  @Test
  fun atMatchesAHandWorkedTrilinear() {
    val table = CubeLut.parse(curved)

    // Red 0.3 sits at 0.6 of the first cell, whose ends are 0 and 0.25: 0.15.
    // Green 0.7 at 0.4 of the second, blue 0.2 at 0.4 of the first: green reads 0.25 then 0.5 near, 0.375 then 0.625
    // far, so 0.35 near and 0.475 far, and 0.4 at blue 0.2. Blue reads 1 - 0.2 = 0.8.
    val read = table.at(0.3f, 0.7f, 0.2f)

    read[0] shouldBe (0.15f plusOrMinus TOLERANCE)
    read[1] shouldBe (0.4f plusOrMinus TOLERANCE)
    read[2] shouldBe (0.8f plusOrMinus TOLERANCE)
  }

  @Test
  fun atHoldsACodeOutsideTheRangeAtTheGridsEdge() {
    val table = CubeLut.parse(curved)

    table.at(-0.5f, 1.5f, 0.5f).toList() shouldBe table.at(0f, 1f, 0.5f).toList()
  }

  @Test
  fun aDomainIsAppliedAtMidRange() {
    // The file spans 0 to 2 on every channel, so code 0.5 sits a quarter of the way up the file's 3-point grid.
    val table =
      CubeLut.parse(
        "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n" +
          cubeText(3) { red, green, blue -> floatArrayOf(red, green, blue * blue) },
      )

    val read = table.at(0.5f, 0.25f, 0.75f)

    // Half a cell up the file's first cell, whose entries are 0 and 0.5.
    read[0] shouldBe (0.25f plusOrMinus TOLERANCE)
    read[1] shouldBe (0.125f plusOrMinus TOLERANCE)
    // Three quarters up the file's first blue cell, whose entries are 0 and 0.25.
    read[2] shouldBe (0.1875f plusOrMinus TOLERANCE)
  }

  @Test
  fun aDomainWithAFloorSubtractsIt() {
    val table =
      CubeLut.parse(
        "DOMAIN_MIN 0.5 0.5 0.5\nDOMAIN_MAX 1 1 1\n" +
          cubeText(2) { red, green, blue -> floatArrayOf(red, green, blue) },
      )

    // Code 0.75 is halfway through a domain of 0.5 to 1, and anything under 0.5 holds the floor.
    table.at(0.75f, 0.75f, 0.25f)[0] shouldBe (0.5f plusOrMinus TOLERANCE)
    table.at(0.75f, 0.75f, 0.25f)[2] shouldBe (0f plusOrMinus TOLERANCE)
  }

  @Test
  fun inputRangeKeywordReadsAsADomain() {
    val table =
      CubeLut.parse(
        "LUT_3D_INPUT_RANGE 0 2\n" + cubeText(2) { red, green, blue -> floatArrayOf(red, green, blue) },
      )

    table.at(0.5f, 0.5f, 0.5f)[1] shouldBe (0.25f plusOrMinus TOLERANCE)
  }

  @Test
  fun anEntryPastAnSdrCodeIsHeldAtItsEnd() {
    val table = CubeLut.parse(cubeText(2) { red, green, blue -> floatArrayOf(red * 2f - 0.5f, green, blue) })

    table.at(1f, 0f, 0f)[0] shouldBe 1f
    table.at(0f, 0f, 0f)[0] shouldBe 0f
    // The clamp sits on the entries, so a read between them mixes the held ends.
    table.at(0.5f, 0f, 0f)[0] shouldBe (0.5f plusOrMinus TOLERANCE)
  }

  @Test
  fun aOneDimensionalTableIsRefusedByName() {
    assertFailsWith<IllegalArgumentException> {
      CubeLut.parse("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n")
    }.message!! shouldContain "LUT_1D_SIZE"
  }

  @Test
  fun aFileWithNoSizeIsRefused() {
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("# nothing here\n") }
      .message!! shouldContain "LUT_3D_SIZE"
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("0 0 0\n") }.message!! shouldContain "Line 1"
  }

  @Test
  fun aSizeAtEachEdgeOfTheRangeIsReadAndPastItRefused() {
    CubeLut.parse(cubeText(CubeLut.MIN_SIZE) { red, green, blue -> floatArrayOf(red, green, blue) }).size shouldBe 2
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 1\n0 0 0\n") }
      .message!! shouldContain "outside 2 to 129"
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 130\n") }
      .message!! shouldContain "outside 2 to 129"
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 99999999999\n") }
      .message!! shouldContain "outside 2 to 129"
    CubeLut.identity(CubeLut.MAX_SIZE).size shouldBe CubeLut.MAX_SIZE
  }

  @Test
  fun aSizeTheTextCannotHoldIsRefusedAheadOfAnyTable() {
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 129\n0 0 0\n") }
      .message!! shouldContain "takes at least"
  }

  @Test
  fun aWrongRowCountIsRefusedEitherWay() {
    assertFailsWith<IllegalArgumentException> {
      CubeLut.parse("LUT_3D_SIZE 2\n" + "0 0 0\n".repeat(7))
    }.message!! shouldContain "has 7"
    assertFailsWith<IllegalArgumentException> {
      CubeLut.parse("LUT_3D_SIZE 2\n" + "0 0 0\n".repeat(9))
    }.message!! shouldContain "Line 10"
  }

  @Test
  fun aBadRowIsRefusedWithItsLine() {
    val eight = "0 0 0\n".repeat(7)
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 2\n${eight}0 0\n") }
      .message!! shouldContain "Line 9"
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 2\n${eight}0 0 0 0\n") }
      .message!! shouldContain "Line 9"
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 2\n${eight}0 0 NaN\n") }
      .message!! shouldContain "finite"
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 2\n${eight}0 0 1e999\n") }
      .message!! shouldContain "finite"
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 2\n${eight}0 0 x\n") }
      .message!! shouldContain "finite"
  }

  @Test
  fun anUnknownKeywordIsRefusedByName() {
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("SHAPER 2\nLUT_3D_SIZE 2\n") }
      .message!! shouldContain "SHAPER"
  }

  @Test
  fun aSecondSizeOrAKeywordInsideTheRowsIsRefused() {
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 2\nLUT_3D_SIZE 2\n" + "0 0 0\n".repeat(8)) }
      .message!! shouldContain "second"
    assertFailsWith<IllegalArgumentException> {
      CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\nDOMAIN_MAX 1 1 1\n" + "0 0 0\n".repeat(7))
    }.message!! shouldContain "DOMAIN_MAX"
  }

  @Test
  fun aDomainThatDoesNotRiseIsRefused() {
    val rows = cubeText(2) { red, green, blue -> floatArrayOf(red, green, blue) }
    assertFailsWith<IllegalArgumentException> { CubeLut.parse("DOMAIN_MIN 1 0 0\nDOMAIN_MAX 1 1 1\n$rows") }
      .message!! shouldContain "domain"
  }

  @Test
  fun textPastTheBoundIsRefusedByName() {
    val text = cubeText(2) { red, green, blue -> floatArrayOf(red, green, blue) }

    CubeLut.parse(text, text.length).size shouldBe 2
    assertFailsWith<IllegalArgumentException> { CubeLut.parse(text, text.length - 1) }
      .message!! shouldContain "${text.length - 1} characters"
  }

  @Test
  fun blendedIsTheMeanOfIdentityAndFullOffGrid() {
    val table = CubeLut.parse(curved)
    val identity = CubeLut.identity(table.size)
    val half = table.blended(0.5f)

    val code = floatArrayOf(0.31f, 0.67f, 0.13f)
    val full = table.at(code[0], code[1], code[2])
    val none = identity.at(code[0], code[1], code[2])
    val read = half.at(code[0], code[1], code[2])
    (0..2).forEach { channel ->
      read[channel] shouldBe ((full[channel] + none[channel]) / 2f plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun blendedAtItsEndsIsIdentityAndItself() {
    val table = CubeLut.parse(curved)

    table.blended(0f) shouldBe CubeLut.identity(table.size)
    table.blended(1f) shouldBe table
  }

  @Test
  fun tablesCompareByContent() {
    val table = CubeLut.parse(curved)

    table shouldBe CubeLut.parse(curved)
    table.hashCode() shouldBe CubeLut.parse(curved).hashCode()
    (table == CubeLut.identity(table.size)) shouldBe false
    (table == table.blended(0.5f)) shouldBe false
  }

  @Test
  fun identityIsSpottedAtAFilesPrintedPrecision() {
    val printed = cubeText(4) { red, green, blue -> floatArrayOf(red, green, blue) }

    CubeLut.parse(printed).isIdentity shouldBe true
    CubeLut.identity(CubeLut.MIN_SIZE).isIdentity shouldBe true
    CubeLut.parse(curved).isIdentity shouldBe false
  }
}

internal const val TOLERANCE = 1e-5f

/**
 * The text of a `.cube` file at [size], with each row worked out by [entry] from its grid position in `0f..1f`.
 */
internal fun cubeText(
  size: Int,
  entry: (red: Float, green: Float, blue: Float) -> FloatArray,
): String =
  buildString {
    append("LUT_3D_SIZE ").append(size).append('\n')
    val last = (size - 1).toFloat()
    for (blue in 0 until size) {
      for (green in 0 until size) {
        for (red in 0 until size) {
          val row = entry(red / last, green / last, blue / last)
          append(row[0])
            .append(' ')
            .append(row[1])
            .append(' ')
            .append(row[2])
            .append('\n')
        }
      }
    }
  }
