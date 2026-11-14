package dev.jordond.filmstrip.transform.internal.resolve

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.effect.EffectId

/**
 * One audio effect, lowered, still carrying the id it came from.
 *
 * The id travels with the platform object so an engine that cannot use what a resolver handed back can say which
 * effect it was.
 *
 * @property spec The spec the effect was lowered from, which [runsAs] tells a changed effect from an unchanged one by.
 * Null for an effect built without one, which is only ever the same as itself.
 */
@InternalFilmstripApi
public class ResolvedAudioEffect(
  public val specId: EffectId,
  public val effect: PlatformAudioEffect,
  public val spec: AudioEffectSpec? = null,
)

/**
 * Whether these are the effects [standing] holds, spec for spec and in the same order.
 *
 * A plan resolves every effect again, so the platform objects differ even when nothing about the effects changed.
 * Every host that swaps kernels while it plays asks this first and keeps the kernels it is running, and what they
 * have built up, when it answers true.
 */
@InternalFilmstripApi
public fun List<ResolvedAudioEffect>.runsAs(standing: List<ResolvedAudioEffect>): Boolean =
  size == standing.size &&
    indices.all { index ->
      val mine = this[index]
      val theirs = standing[index]
      mine === theirs || (mine.spec != null && mine.spec == theirs.spec)
    }
