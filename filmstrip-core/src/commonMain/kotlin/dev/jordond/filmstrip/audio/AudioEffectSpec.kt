package dev.jordond.filmstrip.audio

import dev.jordond.filmstrip.effect.EffectId

/**
 * A declaration of an audio effect, as plain data with no platform types and no signal processing.
 *
 * An [AudioEffectResolver] turns one spec into a platform object each time a pipeline is built. Declared on a clip, a
 * track or a composition through their `audioEffects`. A clip's effects run first, then its track's, both on the
 * clip's audio before its level and fades are applied. A composition's effects run on the finished mix. Within one
 * scope effects run in the order they were declared.
 *
 * Implementations must compare by value, which a `data class` or `@Poko` gives for free. filmstrip decides whether a
 * composition changed by comparing specs, so one left on identity equality makes every rebuilt edit look different.
 */
public interface AudioEffectSpec {
  /**
   * Stable identifier that names the effect in a plan and in diagnostics.
   *
   * It is the same for every instance of one kind of effect.
   */
  public val id: EffectId

  /**
   * Whether this effect leaves every sample as it was, as configured.
   *
   * Defaults to `false`. When `true` the planner takes the effect out of the edit before anything is negotiated, so no
   * backend is handed it, and lists it on the plan's inert notices.
   */
  public val isInert: Boolean
    get() = false
}
