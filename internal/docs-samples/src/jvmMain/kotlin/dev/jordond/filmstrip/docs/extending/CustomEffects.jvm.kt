package dev.jordond.filmstrip.docs.extending

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities

// region jvm
internal fun softFocusOnJvm(
  spec: SoftFocus,
  capabilities: RenderCapabilities,
  attributes: Attributes,
): EffectResolution? {
  if (capabilities.api != RenderApi.FilterGraph) return null

  val sigma = spec.radius * attributes.inputSize.height
  val blur = FilterNode("gblur", "sigma" to sigma.toString())
  return EffectResolution.Resolved(PlatformEffect(FilterFragment(chain = listOf(blur))))
}
// endregion

internal actual fun softFocusOn(
  spec: SoftFocus,
  capabilities: RenderCapabilities,
  attributes: Attributes,
): EffectResolution? = softFocusOnJvm(spec, capabilities, attributes)
