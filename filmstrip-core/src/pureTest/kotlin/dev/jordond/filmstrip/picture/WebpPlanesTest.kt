package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.test.LossyWebpStill
import dev.jordond.filmstrip.test.LossyWebpStills
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WebpPlanesTest {
  @Test
  fun `every lossy still converts to exactly what dwebp wrote`() {
    for (still in LossyWebpStills.ALL) {
      withClue(still.name) { convert(still).toList() shouldBe still.truth().toList() }
    }
  }

  @Test
  fun `the last column of an odd width still is read from the last chroma column`() {
    val still = LossyWebpStills.ODD_WIDTH
    val rgba = convert(still)
    val truth = still.truth()

    still.width % 2 shouldBe 1
    for (row in 0 until still.height) {
      val at = (row * still.width + still.width - 1) * 4
      rgba.slice(at until at + 4) shouldBe truth.slice(at until at + 4)
    }
  }

  @Test
  fun `the last row of an odd height still is read from the last chroma row`() {
    val still = LossyWebpStills.ODD_HEIGHT
    val rgba = convert(still)
    val truth = still.truth()
    val at = (still.height - 1) * still.width * 4

    still.height % 2 shouldBe 1
    rgba.slice(at until at + still.width * 4) shouldBe truth.slice(at until at + still.width * 4)
  }

  @Test
  fun `a still with no alpha plane is opaque`() {
    val still = LossyWebpStills.OPAQUE_ODD
    val rgba = convert(still)

    still.alpha() shouldBe null
    (0 until still.width * still.height).map { rgba[it * 4 + 3].toInt() and 0xFF }.toSet() shouldBe setOf(255)
  }

  @Test
  fun `a plane of the wrong size is refused`() {
    val still = LossyWebpStills.EVEN

    assertFailsWith<IllegalArgumentException> {
      webpRgbaOf(still.width, still.height, still.luma().copyOf(1), still.blueDifference(), still.redDifference(), null)
    }
    assertFailsWith<IllegalArgumentException> {
      webpRgbaOf(still.width, still.height, still.luma(), still.blueDifference().copyOf(1), still.redDifference(), null)
    }
  }

  private fun convert(still: LossyWebpStill): ByteArray =
    webpRgbaOf(
      still.width,
      still.height,
      still.luma(),
      still.blueDifference(),
      still.redDifference(),
      still.alpha(),
    )
}
