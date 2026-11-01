package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.webcodecs.internal.WEBGL2_MIN_TEXTURE_SIDE
import dev.jordond.filmstrip.webcodecs.internal.webGlTextureSideProbe
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the page's longest texture side is floored at what WebGL2 promises.
 */
class TextureSideProbeTest {
  @Test
  fun aPageThatGivesNoContextReadsTheWebGl2Floor() {
    assertEquals(WEBGL2_MIN_TEXTURE_SIDE, webGlTextureSideProbe { null }.side())
  }

  @Test
  fun aReadBelowTheWebGl2FloorIsHeldToIt() {
    assertEquals(WEBGL2_MIN_TEXTURE_SIDE, webGlTextureSideProbe { WEBGL2_MIN_TEXTURE_SIDE - 1 }.side())
  }
}
