package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two HDR answers media3 gives a resolver, which are separate questions about the same export.
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

  private fun capabilities(hdr: Boolean) = media3RenderCapabilities(Size(1280, 720), hdr)
}
