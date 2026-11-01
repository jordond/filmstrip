package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.effect.RenderFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two HDR answers media3 gives a resolver, which are separate questions about the same export, and the texture
 * side it reports.
 */
class Media3RenderCapabilitiesTest {
  @Test
  fun `tone mapping is offered whether or not the export keeps a grade`() {
    assertTrue(capabilities(hdr = false).has(RenderFeature.HdrToneMapping))
    assertTrue(capabilities(hdr = true).has(RenderFeature.HdrToneMapping))
  }

  @Test
  fun `hdr rendering follows the grade reaching the encoder`() {
    assertFalse(capabilities(hdr = false).supportsHdr)
    assertTrue(capabilities(hdr = true).supportsHdr)
  }

  // A device whose textures hold less than a 4K frame on a side reports that side, not a floor above it.
  @Test
  fun `the texture side is the one the device gives`() {
    assertEquals(ES3_MIN_TEXTURE_SIDE, capabilities(hdr = false).maxTextureSize)
  }

  private fun capabilities(hdr: Boolean) = media3RenderCapabilities(hdr, maxTextureSide = ES3_MIN_TEXTURE_SIDE)
}
