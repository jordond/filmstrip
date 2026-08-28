package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.coreImageRenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two HDR answers Core Image gives a resolver, which are separate questions about the same
 * export.
 */
class CoreImageCapabilitiesTest {
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

  private fun capabilities(hdr: Boolean) = coreImageRenderCapabilities(Size(1280, 720), hdr)
}
