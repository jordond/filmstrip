package dev.jordond.filmstrip.effects.filters.lut

import dev.jordond.filmstrip.InternalFilmstripApi
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

@OptIn(InternalFilmstripApi::class)
class LutLoweringTest {
  private val curved =
    CubeLut.parse(
      "LUT_3D_SIZE 2\n" +
        "0.0001 0.000012 0\n1 0.25 0.5\n0.75 0.125 0.3\n0.2 0.6 0.7\n" +
        "0.33 0.44 0.55\n0.66 0.77 0.88\n0.11 0.22 0.99\n0.5 0.5 0.5\n",
    )

  @Test
  fun cubeTextRoundTripsToTheTableAtMidRange() {
    val back = CubeLut.parse(cubeText(curved))

    back.size shouldBe curved.size
    val read = back.at(0.31f, 0.67f, 0.13f)
    val want = curved.at(0.31f, 0.67f, 0.13f)
    (0..2).forEach { read[it] shouldBe (want[it] plusOrMinus 1e-6f) }
  }

  @Test
  fun cubeTextWritesASmallEntryWithNoExponent() {
    val text = cubeText(curved)

    text.substringAfter('\n') shouldNotContain "E"
    text.lineSequence().drop(1).first() shouldBe "0.0001000 0.0000120 0.0000000"
  }

  @Test
  fun aSidecarHoldsEveryEntryHalfACodeUp() {
    val rows =
      lutFragment(Lut(curved))
        .sidecars
        .single()
        .bytes
        .decodeToString()
        .lines()

    rows[1].split(' ').map { it.toDouble() } shouldContainAllNear
      listOf(0.0001 + HALF_CODE, 0.000012 + HALF_CODE, HALF_CODE)
    rows[2].split(' ').map { it.toDouble() } shouldContainAllNear
      listOf(1.0 + HALF_CODE, 0.25 + HALF_CODE, 0.5 + HALF_CODE)
  }

  @Test
  fun fragmentReadsTheBlendedTableAsASidecar() {
    val fragment = lutFragment(Lut(curved, 0.5f))

    fragment.sidecars
      .single()
      .bytes
      .decodeToString() shouldBe cubeText(curved.blended(0.5f), LUT3D_ROUNDING)
    val node = fragment.chain.single { it.name == "lut3d" }
    val arguments = node.arguments.associate { it.key to it.value }
    arguments["interp"] shouldBe "trilinear"
    arguments["file"] shouldBe fragment.sidecars.single().placeholder
  }

  private infix fun List<Double>.shouldContainAllNear(want: List<Double>) {
    size shouldBe want.size
    forEachIndexed { index, read -> read shouldBe (want[index] plusOrMinus 1e-6) }
  }

  private companion object {
    // The half code the sidecar has to carry, written out so a change to the shared bias fails here.
    const val HALF_CODE = 0.5 / 255.0
  }
}
