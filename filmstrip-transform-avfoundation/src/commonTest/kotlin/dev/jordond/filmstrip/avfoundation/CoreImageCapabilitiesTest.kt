package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.export.coreImageRenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.test.Test

/**
 * The two HDR answers Core Image gives a resolver, which are separate questions about the same
 * export.
 */
class CoreImageCapabilitiesTest {
  @Test
  fun `tone mapping is offered whether or not the export keeps a grade`() {
    capabilities(hdr = false).has(RenderFeature.HdrToneMapping).shouldBeTrue()
    capabilities(hdr = true).has(RenderFeature.HdrToneMapping).shouldBeTrue()
  }

  @Test
  fun `hdr rendering follows the grade reaching the encoder`() {
    capabilities(hdr = false).supportsHdr.shouldBeFalse()
    capabilities(hdr = true).supportsHdr.shouldBeTrue()
  }

  private fun capabilities(hdr: Boolean) = coreImageRenderCapabilities(Size(1280, 720), hdr)
}
