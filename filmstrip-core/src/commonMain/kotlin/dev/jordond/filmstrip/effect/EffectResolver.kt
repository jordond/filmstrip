package dev.jordond.filmstrip.effect

import dev.drewhamilton.poko.Poko

/**
 * Realizes an [EffectSpec] as something the current platform can render.
 *
 * Return `null` to decline a spec and the next resolver is asked. Return
 * [EffectResolution.Unsupported] to claim a spec and refuse it, which ends the chain so a later
 * resolver cannot substitute a different rendering.
 *
 * Registered on [dev.jordond.filmstrip.FilmstripBuilder] through
 * [dev.jordond.filmstrip.FilmstripBuilder.addEffectResolver], in registration order: the first
 * resolver to return a non-null result wins. Export engines are the same shape, registered through
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
   * @param context whether the result is for realtime preview or an offline export. A resolver may return a cheaper
   * approximation for [ExecutionContext.Preview], and must not for [ExecutionContext.Export].
   * @param attributes cross-cutting facts the pipeline already resolved, such as the output frame size every
   * normalized parameter is measured against.
   * @return `null` to pass to the next resolver, or a resolution that claims the spec.
   */
  public fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    context: ExecutionContext,
    attributes: Attributes,
  ): EffectResolution?
}

/**
 * Where an effect is being asked to run.
 */
public enum class ExecutionContext {
  /**
   * Realtime. Must not drop frames, and may trade exactness for latency.
   */
  Preview,

  /**
   * Offline. May be slow, and must be exact and deterministic.
   */
  Export,
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
   * work starts. [DegradationReason.RealtimeApproximation] must never appear in an
   * [ExecutionContext.Export] resolution.
   *
   * @property effect The platform object to render with.
   * @property reason What was given up.
   * @property message A human-readable description, safe to show and unsuitable for parsing.
   */
  @Poko
  public class Degraded(
    public val effect: PlatformEffect,
    public val reason: DegradationReason,
    public val message: String,
  ) : EffectResolution

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
   * Converted between colour spaces, including HDR tone-mapped to SDR.
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
}
