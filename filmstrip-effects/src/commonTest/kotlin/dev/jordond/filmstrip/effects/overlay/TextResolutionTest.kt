package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

class TextResolutionTest {
  private val caption = TextOverlay("caption")

  @Test
  fun `a drawn raster is handed on`() {
    val resolved = EffectResolution.Unsupported(caption.id, "handed on")
    var handed: String? = null

    val resolution =
      caption.resolvedFrom(DRAWS_TEXT, { TextRaster.Drawn("pixels") }) {
        handed = it
        resolved
      }

    handed shouldBe "pixels"
    resolution shouldBeSameInstanceAs resolved
  }

  @Test
  fun `text with nothing to draw is refused as empty`() {
    caption.resolvedFrom(DRAWS_TEXT, { TextRaster.Empty }) { error("Nothing was drawn.") } shouldBe
      EffectResolution.Unsupported(caption.id, EMPTY_TEXT)
  }

  @Test
  fun `a refused raster is refused with its own reason`() {
    caption.resolvedFrom(DRAWS_TEXT, { TextRaster.Refused("too large") }) { error("Nothing was drawn.") } shouldBe
      EffectResolution.Unsupported(caption.id, "too large")
  }

  @Test
  fun `a device that cannot draw text is refused before anything is drawn`() {
    var rasterized = false

    val resolution =
      caption.resolvedFrom(
        capabilities(emptySet()),
        {
          rasterized = true
          TextRaster.Drawn("pixels")
        },
      ) { error("Nothing was drawn.") }

    resolution shouldBe EffectResolution.Unsupported(caption.id, NO_TEXT_RENDERING)
    rasterized shouldBe false
  }

  private companion object {
    val DRAWS_TEXT = capabilities(setOf(RenderFeature.TextRendering))

    fun capabilities(features: Set<RenderFeature>): RenderCapabilities =
      RenderCapabilities(
        api = RenderApi.Metal,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = false,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 4_096,
        features = features,
      )
  }
}
