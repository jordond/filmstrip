package dev.jordond.filmstrip.docs.extending

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.allBackends
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.media.MediaSource

// region spec
internal data class SoftFocus(
  val radius: Float,
) : EffectSpec {
  override val id: String get() = "acme.softfocus"

  override val stage: EffectStage get() = EffectStage.Spatial
}

internal fun EffectsBuilder.softFocus(radius: Float): EffectsBuilder = add(SoftFocus(radius))
// endregion

internal fun softFocusEdit(): Composition {
  // region use
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4")) {
        effects { softFocus(radius = 0.01f) }
      }
    }
  // endregion
  return composition
}

// region resolver
internal class SoftFocusResolver : EffectResolver {
  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    if (spec !is SoftFocus) return null

    return softFocusOn(spec, capabilities, attributes)
  }
}
// endregion

internal expect fun softFocusOn(
  spec: SoftFocus,
  capabilities: RenderCapabilities,
  attributes: Attributes,
): EffectResolution?

internal fun softFocusFilmstrip(): Filmstrip {
  // region register
  val filmstrip =
    Filmstrip {
      allBackends()
      addEffectResolver(SoftFocusResolver())
    }
  // endregion
  return filmstrip
}
