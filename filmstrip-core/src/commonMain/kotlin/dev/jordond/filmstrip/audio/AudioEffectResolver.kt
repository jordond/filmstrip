package dev.jordond.filmstrip.audio

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.EffectId

/**
 * Realizes an [AudioEffectSpec] as something the current platform can run.
 *
 * Return `null` to decline a spec and the next resolver is asked. Return [AudioEffectResolution.Unsupported] to claim
 * a spec and refuse it, which ends the chain so a later resolver cannot substitute a different sound.
 *
 * Registered through [dev.jordond.filmstrip.FilmstripBuilder.addAudioEffectResolver] and asked newest registration
 * first. A spec is resolved once for every run of audio it is laid over, and again after every change to it.
 */
public fun interface AudioEffectResolver {
  /**
   * Realizes [spec], or declines it.
   *
   * @param spec The effect that was declared.
   * @param attributes What the pipeline settled about the audio the effect runs on.
   * @return `null` to pass to the next resolver, or a resolution that claims the spec.
   */
  public fun resolve(
    spec: AudioEffectSpec,
    attributes: AudioAttributes,
  ): AudioEffectResolution?
}

/**
 * What the pipeline settled about the audio an effect runs on before the effect is resolved.
 *
 * Constructed by filmstrip only. New properties may be added in a later release.
 *
 * @property span The composition time range the audio covers. A clip or track effect sees its clip's slot on the
 * timeline, a composition effect sees the whole composition.
 */
@Poko
public class AudioAttributes
  @InternalFilmstripApi
  constructor(
    public val span: TimeRange,
  )

/**
 * The outcome of asking one resolver to realize one audio spec.
 */
public sealed interface AudioEffectResolution {
  /**
   * Realized exactly as declared.
   *
   * @property effect The platform object to run.
   */
  @Poko
  public class Resolved(
    public val effect: PlatformAudioEffect,
  ) : AudioEffectResolution

  /**
   * Realized, but not exactly as declared.
   *
   * The resolution keeps its own copy of the causes, so changing the list passed in afterwards does not reach it.
   *
   * @property effect The platform object to run.
   * @property causes Everything that was given up, in the order the causes apply. Never empty.
   * @throws IllegalArgumentException when [causes] is empty.
   */
  public class Degraded(
    public val effect: PlatformAudioEffect,
    causes: List<Degradation>,
  ) : AudioEffectResolution {
    public val causes: List<Degradation> = causes.toList()

    init {
      require(this.causes.isNotEmpty()) { "A degraded resolution needs at least one cause" }
    }

    override fun equals(other: Any?): Boolean =
      this === other || (other is Degraded && effect == other.effect && causes == other.causes)

    override fun hashCode(): Int = 31 * effect.hashCode() + causes.hashCode()

    override fun toString(): String = "Degraded(effect=$effect, causes=$causes)"
  }

  /**
   * This resolver owns the spec but cannot realize it here. Ends the chain for this spec.
   *
   * @property specId The [AudioEffectSpec.id] that was refused.
   * @property message A human-readable description, safe to show and unsuitable for parsing.
   */
  @Poko
  public class Unsupported(
    public val specId: EffectId,
    public val message: String,
  ) : AudioEffectResolution

  /**
   * The spec could not be resolved because a platform read it needs failed. Ends the chain for this spec.
   *
   * The failure says nothing about whether the platform can realize the spec, so a later call may succeed. The export
   * is refused with [error], and dropping unsupported effects never drops this one.
   *
   * @property error The error the export is refused with.
   */
  @Poko
  public class Failed(
    public val error: FilmstripError,
  ) : AudioEffectResolution
}

/**
 * Asks each resolver in turn to realize [spec] and answers with the first claim, or null when every one declines.
 *
 * @param spec The effect that was declared.
 * @param attributes What the pipeline settled about the audio the effect runs on.
 */
@InternalFilmstripApi
public fun List<AudioEffectResolver>.resolveAudioEffect(
  spec: AudioEffectSpec,
  attributes: AudioAttributes,
): AudioEffectResolution? = firstNotNullOfOrNull { it.resolve(spec, attributes) }
