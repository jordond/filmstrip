package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.webcodecs.internal.WEBGL2_MIN_TEXTURE_SIDE
import dev.jordond.filmstrip.webcodecs.internal.webGlTextureSideProbe
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * How the page's longest texture side is floored at what WebGL2 promises.
 */
class TextureSideProbeTest {
  @Test
  fun aPageThatGivesNoContextReadsTheWebGl2Floor() {
    webGlTextureSideProbe { null }.side() shouldBe WEBGL2_MIN_TEXTURE_SIDE
  }

  @Test
  fun aReadBelowTheWebGl2FloorIsHeldToIt() {
    webGlTextureSideProbe { WEBGL2_MIN_TEXTURE_SIDE - 1 }.side() shouldBe WEBGL2_MIN_TEXTURE_SIDE
  }
}
