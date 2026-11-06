package dev.jordond.filmstrip.docs.extending

import androidx.media3.effect.GaussianBlur
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities

// region android
internal fun softFocusOnAndroid(
  spec: SoftFocus,
  capabilities: RenderCapabilities,
  attributes: Attributes,
): EffectResolution? {
  if (capabilities.api != RenderApi.OpenGlEs) return null

  val sigma = spec.radius * attributes.inputSize.height
  return EffectResolution.Resolved(PlatformEffect(GaussianBlur(sigma)))
}
// endregion

internal actual fun softFocusOn(
  spec: SoftFocus,
  capabilities: RenderCapabilities,
  attributes: Attributes,
): EffectResolution? = softFocusOnAndroid(spec, capabilities, attributes)
