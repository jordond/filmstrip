package dev.jordond.filmstrip.docs.extending

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import platform.CoreImage.CIFilter
import platform.CoreImage.filterWithName
import platform.Foundation.setValue

// region ios
internal fun softFocusOnIos(
  spec: SoftFocus,
  capabilities: RenderCapabilities,
  attributes: Attributes,
): EffectResolution? {
  if (capabilities.api != RenderApi.Metal && capabilities.api != RenderApi.CoreImage) return null

  val radius = spec.radius * attributes.inputSize.height
  val step =
    CoreImageEffect { image, _ ->
      val blur = CIFilter.filterWithName("CIGaussianBlur") ?: return@CoreImageEffect image
      blur.setValue(image, forKey = "inputImage")
      blur.setValue(radius.toDouble(), forKey = "inputRadius")
      blur.outputImage ?: image
    }
  return EffectResolution.Resolved(PlatformEffect(step))
}
// endregion

internal actual fun softFocusOn(
  spec: SoftFocus,
  capabilities: RenderCapabilities,
  attributes: Attributes,
): EffectResolution? = softFocusOnIos(spec, capabilities, attributes)
