package dev.jordond.filmstrip.effect

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EditComposition

/**
 * Realizes an [EffectSpec] as something the current platform can render.
 *
 * Return `null` to decline a spec and the next resolver is asked. Return
 * [EffectResolution.Unsupported] to claim a spec and refuse it, which ends the chain so a later
 * resolver cannot substitute a different rendering.
 *
 * Registered on [dev.jordond.filmstrip.FilmstripBuilder] through
 * [dev.jordond.filmstrip.FilmstripBuilder.addEffectResolver] and asked newest registration first:
 * the first resolver to return a non-null result wins. Export engines are the same shape, registered through
 * [dev.jordond.filmstrip.FilmstripBuilder.addExportEngineFactory]. A third-party render backend is
 * written the same way filmstrip's own are: an extension function on `FilmstripBuilder` that
 * registers a resolver, an export engine factory, or both. The four built-in export engines are
 * ordinary instances of that pattern, so registering one after them makes it win.
 *
 * Implementations are called once per pipeline and must return a fresh platform object each time.
 * Share the spec, not the platform object it resolves to.
 */
public fun interface EffectResolver {
  /**
   * Realises [spec], or declines it.
   *
   * @param capabilities what this device can render, right now.
   * @param attributes cross-cutting facts the pipeline already resolved, such as the output frame size every
   * normalized parameter is measured against.
   * @return `null` to pass to the next resolver, or a resolution that claims the spec.
   */
  public fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution?

  /**
   * Gets ready to resolve the effects in [composition], before any of them is asked for.
   *
   * Every export engine calls this before each negotiation of [composition], so work a platform can
   * only do asynchronously, such as decoding an overlay image in a browser, is done by the time
   * [resolve] needs it. The default does nothing.
   */
  public suspend fun prepare(composition: EditComposition) {}
}

/**
 * The outcome of asking one resolver to realize one spec.
 */
public sealed interface EffectResolution {
  /**
   * Realised exactly as declared.
   *
   * @property effect The platform object to render with.
   */
  @Poko
  public class Resolved(
    public val effect: PlatformEffect,
  ) : EffectResolution

  /**
   * Realised, but not exactly as declared.
   *
   * The caller decides whether that is acceptable, and `plan()` surfaces it before any long-running
   * work starts.
   *
   * The resolution keeps its own copy of the causes, so changing the list passed in afterwards does not reach it.
   *
   * @property effect The platform object to render with.
   * @property causes Everything that was given up, in the order the causes apply. Never empty.
   * @throws IllegalArgumentException when [causes] is empty.
   */
  public class Degraded(
    public val effect: PlatformEffect,
    causes: List<Degradation>,
  ) : EffectResolution {
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
   * @property specId The [EffectSpec.id] that was refused.
   * @property message A human-readable description, safe to show and unsuitable for parsing.
   */
  @Poko
  public class Unsupported(
    public val specId: String,
    public val message: String,
  ) : EffectResolution
}

/**
 * One way an [EffectResolution.Degraded] differs from what was declared.
 *
 * @property reason What was given up.
 * @property message A human-readable description, safe to show and unsuitable for parsing.
 */
@Poko
public class Degradation(
  public val reason: DegradationReason,
  public val message: String,
)

/**
 * Why a resolution is [EffectResolution.Degraded] rather than exact.
 */
public enum class DegradationReason {
  /**
   * Reduced numeric precision, such as fp16 where fp32 was asked for.
   */
  PrecisionReduced,

  /**
   * A different algorithm with the same intent, such as a separable blur for a true Gaussian.
   */
  ApproximateAlgorithm,

  /**
   * Clamped to the platform's maximum texture size.
   */
  ResolutionClamped,

  /**
   * Converted between color spaces, including HDR tone-mapped to SDR.
   */
  ColorSpaceConverted,

  /**
   * No GPU path was available and a CPU implementation was substituted.
   */
  SoftwareFallback,

  /**
   * A cheaper kernel was chosen to hold the preview's frame budget. Preview only.
   */
  RealtimeApproximation,

  /**
   * The alpha a picture's file carries was not read, so the picture draws opaque. The color still lands as the file
   * stores it.
   */
  AlphaDiscarded,
}
