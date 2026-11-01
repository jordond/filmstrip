package dev.jordond.filmstrip.effects.overlay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a raster stops fitting a texture side, and what a refusal past it names.
 */
class TextureSideTest {
  @Test
  fun aRasterFitsAtTheSideAndNotOnePixelPastOnEitherSide() {
    assertTrue(fitsTextureSide(MAX_SIDE, MAX_SIDE, MAX_SIDE))
    assertTrue(fitsTextureSide(MAX_SIDE / 2, MAX_SIDE / 3, MAX_SIDE))
    assertFalse(fitsTextureSide(MAX_SIDE + 1, 1, MAX_SIDE))
    assertFalse(fitsTextureSide(1, MAX_SIDE + 1, MAX_SIDE))
  }

  @Test
  fun aRefusalNamesTheSizeTheSideAndTheGraphicsApi() {
    val reasons =
      listOf(textPastTextureSide(WIDTH, HEIGHT, MAX_SIDE, API), picturePastTextureSide(WIDTH, HEIGHT, MAX_SIDE, API))

    for (reason in reasons) {
      assertTrue("$WIDTH by $HEIGHT" in reason, reason)
      assertTrue("A $API texture" in reason, reason)
      assertTrue("$MAX_SIDE pixels" in reason, reason)
    }
  }

  private companion object {
    const val MAX_SIDE = 4096
    const val WIDTH = 5000
    const val HEIGHT = 300
    const val API = "OpenGL ES"
  }
}
