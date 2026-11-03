package dev.jordond.filmstrip.effects.overlay

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Where a raster stops fitting a texture side, and what a refusal past it names.
 */
class TextureSideTest {
  @Test
  fun aRasterFitsAtTheSideAndNotOnePixelPastOnEitherSide() {
    fitsTextureSide(MAX_SIDE, MAX_SIDE, MAX_SIDE) shouldBe true
    fitsTextureSide(MAX_SIDE / 2, MAX_SIDE / 3, MAX_SIDE) shouldBe true
    fitsTextureSide(MAX_SIDE + 1, 1, MAX_SIDE) shouldBe false
    fitsTextureSide(1, MAX_SIDE + 1, MAX_SIDE) shouldBe false
  }

  @Test
  fun aRefusalNamesTheSizeTheSideAndTheGraphicsApi() {
    val reasons =
      listOf(textPastTextureSide(WIDTH, HEIGHT, MAX_SIDE, API), picturePastTextureSide(WIDTH, HEIGHT, MAX_SIDE, API))

    for (reason in reasons) {
      withClue(reason) { ("$WIDTH by $HEIGHT" in reason) shouldBe true }
      withClue(reason) { ("A $API texture" in reason) shouldBe true }
      withClue(reason) { ("$MAX_SIDE pixels" in reason) shouldBe true }
    }
  }

  private companion object {
    const val MAX_SIDE = 4096
    const val WIDTH = 5000
    const val HEIGHT = 300
    const val API = "OpenGL ES"
  }
}
