package dev.jordond.filmstrip.docs.extending

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities

// region web
internal fun softFocusOnWeb(
  spec: SoftFocus,
  capabilities: RenderCapabilities,
  attributes: Attributes,
): EffectResolution? {
  if (capabilities.api != RenderApi.WebGl) return null

  return EffectResolution.Unsupported(spec.id, "The browser has no pass that can draw a blur.")
}
// endregion

internal actual fun softFocusOn(
  spec: SoftFocus,
  capabilities: RenderCapabilities,
  attributes: Attributes,
): EffectResolution? = softFocusOnWeb(spec, capabilities, attributes)
