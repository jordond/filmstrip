package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.effect.RenderFeature
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The two HDR answers media3 gives a resolver, which are separate questions about the same export, and the texture
 * side it reports.
 */
class Media3RenderCapabilitiesTest {
  @Test
  fun `tone mapping is offered whether or not the export keeps a grade`() {
    capabilities(hdr = false).has(RenderFeature.HdrToneMapping) shouldBe true
    capabilities(hdr = true).has(RenderFeature.HdrToneMapping) shouldBe true
  }

  @Test
  fun `hdr rendering follows the grade reaching the encoder`() {
    capabilities(hdr = false).supportsHdr shouldBe false
    capabilities(hdr = true).supportsHdr shouldBe true
  }

  // A device whose textures hold less than a 4K frame on a side reports that side, not a floor above it.
  @Test
  fun `the texture side is the one the device gives`() {
    capabilities(hdr = false).maxTextureSize shouldBe ES3_MIN_TEXTURE_SIDE
  }

  private fun capabilities(hdr: Boolean) = media3RenderCapabilities(hdr, maxTextureSide = ES3_MIN_TEXTURE_SIDE)
}
