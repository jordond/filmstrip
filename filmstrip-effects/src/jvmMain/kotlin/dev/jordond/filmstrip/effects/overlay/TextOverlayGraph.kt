package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.media.ImageSource

/**
 * Lowers [spec] onto a filter graph fragment that draws its text as a picture over the frames it merges onto.
 *
 * The text is drawn by [rasterizeText] against [Attributes.layoutSize], so a preview breaks its lines where the export
 * does, and placed by [TextOverlay.placedOn] at the size [drawnTextSize] gives it on the frame entering the effect. On
 * an SDR grade the graph opens the raster as a sixteen-bit PNG and reads it at eight bits, the depth the output stores.
 * On an HDR grade the raster goes through [hdrOverlayRaster] and is blended in linear display light. Either way it is
 * drawn by [lowered], the same lowering an [ImageOverlay] takes. An animation that cannot be driven at all is refused
 * before the text is drawn.
 *
 * @return The fragment, or [EffectResolution.Unsupported] when the text or its animation cannot be drawn here.
 */
internal fun filterGraphText(
  spec: TextOverlay,
  capabilities: RenderCapabilities,
  attributes: Attributes,
): EffectResolution {
  spec.animationRefusal(attributes, spec.drawnRangeWithin(attributes.span))?.let {
    return EffectResolution.Unsupported(spec.id, it)
  }
  return spec.resolvedFrom(capabilities, { rasterizeText(spec.text, spec.style, attributes.layoutSize) }) { raster ->
    val transfer = attributes.hdrTransfer
    val picture =
      if (transfer == null) {
        GraphPicture.Opened(
          ImageSource.ofBytes(wideRgbaPng(raster.wideSamples(), raster.size.width, raster.size.height)),
        )
      } else {
        GraphPicture.Graded(hdrOverlayRaster(raster, transfer), transfer)
      }
    PlacedPicture(spec, picture, spec.placedOn(attributes.drawnTextSize(raster.size))).lowered(attributes)
  }
}

// The raster's sixteen-bit samples as the unsigned values a PNG writer takes.
private fun StoredPicture.wideSamples(): IntArray {
  val rgba = (samples as StoredSamples.Rgba16).rgba
  return IntArray(rgba.size) { rgba[it].toInt() and SAMPLE_MASK }
}

private const val SAMPLE_MASK = 0xFFFF
