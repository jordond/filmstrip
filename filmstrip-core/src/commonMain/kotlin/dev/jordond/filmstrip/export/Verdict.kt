package dev.jordond.filmstrip.export

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.ParityNote
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration

/**
 * What this device will do with an edit, decided before any decoding, encoding or writing happens.
 *
 * Returned by [Filmstrip.plan], and the only source of an [ExportPlan].
 */
public sealed interface Verdict {
  /**
   * The edit runs exactly as declared.
   *
   * @property plan What will run.
   */
  @Poko
  public class Capable(
    public val plan: ExportPlan,
  ) : Verdict

  /**
   * The edit runs, but not exactly as declared.
   *
   * @property plan What will run, after every fallback.
   * @property adjustments What filmstrip changed to get there. Never empty.
   */
  @Poko
  public class Degraded(
    public val plan: ExportPlan,
    public val adjustments: List<Adjustment>,
  ) : Verdict

  /**
   * The edit cannot run on this device. There is no plan.
   *
   * @property reasons Why it cannot run.
   * @property withoutUnsupported The plan that would work with the impossible effects removed, or
   *   null when no such plan exists.
   */
  @Poko
  public class Incapable(
    public val reasons: List<ExportError>,
    public val withoutUnsupported: ExportPlan?,
  ) : Verdict
}

/**
 * One thing filmstrip changed to make the export possible. Ordered as applied.
 *
 * @property kind What sort of change this is.
 * @property requested What was asked for, human-readable.
 * @property resolved What will actually happen.
 * @property message A human-readable description of the change.
 */
@Poko
public class Adjustment(
  public val kind: AdjustmentKind,
  public val requested: String,
  public val resolved: String,
  public val message: String,
)

/**
 * What kind of change an [Adjustment] describes.
 *
 * Not every kind needs to reach the user. [HdrToneMapped] and [EffectDropped] change how the output
 * looks next to the preview the user just watched, so surface those.
 */
public enum class AdjustmentKind {
  /**
   * The requested codec was unavailable and a different one was chosen.
   */
  CodecFallback,

  /**
   * Above the encoder's maximum, or odd dimensions rounded to even.
   */
  ResolutionClamped,

  /**
   * Above the encoder's bitrate ceiling.
   */
  BitrateClamped,

  /**
   * Above what the encoder accepts, or above the source's own rate.
   */
  FrameRateClamped,

  /**
   * A grade could not be kept, so it was brought down to SDR.
   *
   * Either the encoder cannot write HDR, or the sources carry transfers that disagree and no one
   * grade describes the output. Asking for [HdrMode.ToneMapToSdr] reports nothing, since that got
   * what it asked for.
   */
  HdrToneMapped,

  /**
   * A resolver returned a degraded realisation of an effect.
   */
  EffectApproximated,

  /**
   * An effect was removed. Only reachable through [Verdict.Incapable.withoutUnsupported].
   */
  EffectDropped,

  /**
   * A hardware path was unavailable and a slower one was substituted.
   */
  SoftwareFallback,

  /**
   * A requested trim strategy was not applicable and a different one ran.
   */
  TrimStrategyChanged,
}

/**
 * What an export will actually do, resolved before it starts.
 *
 * Obtainable only from [Verdict.Capable] or [Verdict.Degraded].
 *
 * @property path Whether the export copies streams, re-encodes only what the trim needs, or
 *   transcodes in full.
 * @property output The resolved output format, after every fallback.
 * @property effectOrder Effects in the exact order they will run, after staging.
 * @property estimate What the export is expected to cost.
 * @property parity The weakest [EffectParity] across [effectOrder]. [EffectParity.Exact] only when
 *   every effect is.
 * @property composition The composition this plan was resolved from.
 * @property spec The spec this plan was resolved from.
 */
public class ExportPlan
  @InternalFilmstripApi
  constructor(
    public val path: ExportPath,
    public val output: OutputFormat,
    public val effectOrder: List<PlannedEffect>,
    public val estimate: ExportEstimate,
    public val parity: EffectParity,
    @property:InternalFilmstripApi public val composition: EditComposition,
    @property:InternalFilmstripApi public val spec: ExportSpec,
  ) {
    override fun toString(): String =
      "ExportPlan(path=$path, output=$output, effects=${effectOrder.size}, parity=$parity)"
  }

/**
 * The output format an [ExportPlan] resolved to.
 *
 * @property size The output frame size, in pixels.
 * @property videoCodec The codec the video is encoded with.
 * @property audioCodec The codec the audio is encoded with.
 * @property bitrate The video bitrate, or null when the encoder chooses it.
 * @property frameRate The output frame rate, or null when it follows the source.
 * @property audioFormat The audio format the whole composition is normalised to, or null when there
 *   is no audio.
 */
@Poko
public class OutputFormat(
  public val size: Size,
  public val videoCodec: VideoCodec,
  public val audioCodec: AudioCodec,
  public val bitrate: Bitrate?,
  public val frameRate: Int?,
  public val audioFormat: AudioFormat?,
)

/**
 * The sample rate and channel count the whole composition is normalised to.
 *
 * Decided once, at plan time. A composition whose channel counts cannot be mixed is refused then
 * rather than part way through an export.
 *
 * @property sampleRate Samples per second.
 * @property channelCount How many audio channels.
 */
@Poko
public class AudioFormat(
  public val sampleRate: Int,
  public val channelCount: Int,
)

/**
 * One effect, resolved: where it runs and how faithfully the preview will show it.
 *
 * @property spec The effect that was asked for.
 * @property stage The stage of the pipeline it runs in.
 * @property parity How closely the preview will match the export.
 * @property note What the divergence is, or null when [parity] is [EffectParity.Exact].
 */
@Poko
public class PlannedEffect(
  public val spec: EffectSpec,
  public val stage: EffectStage,
  public val parity: EffectParity,
  public val note: ParityNote?,
)

/**
 * Which path an export will take.
 */
public enum class ExportPath {
  /**
   * No re-encode at all. Near-instant, and the video is bit-for-bit the source.
   */
  Transmux,

  /**
   * Re-encode only what the trim requires, and stream-copy the rest.
   */
  TrimOptimized,

  /**
   * A full decode, effect and re-encode pass.
   */
  Transcode,
}

/**
 * What an export is expected to cost.
 *
 * @property outputSizeBytesMin The smallest the output is expected to be, or null when it cannot
 *   be estimated, as with a variable bitrate or an unmeasured source.
 * @property outputSizeBytesMax The largest the output is expected to be, or null when it cannot be
 *   estimated.
 * @property approximateDuration Roughly how long the export will take in wall-clock time, or null
 *   when it cannot be estimated.
 * @property isPassthrough True when nothing is re-encoded.
 */
@Poko
public class ExportEstimate(
  public val outputSizeBytesMin: Long?,
  public val outputSizeBytesMax: Long?,
  public val approximateDuration: Duration?,
  public val isPassthrough: Boolean,
)
