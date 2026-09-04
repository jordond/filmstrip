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
}

/**
 * One reason an export re-encodes rather than copying its streams across.
 *
 * A copy is near-instant and lossless, so an export that misses it is worth explaining. Every
 * member names one thing the caller can drop to get the copy back, and [ExportPlan.copyBlockedBy]
 * lists the ones that applied.
 *
 * None of these is an [Adjustment]: the caller asked for an output and got exactly that output, so
 * nothing was changed behind their back and [ExportSpec.strict] has nothing to refuse.
 */
public enum class CopyBlocker {
  /**
   * More than one track, which has to be composited.
   */
  MultipleTracks,

  /**
   * More than one clip, which has to be joined.
   */
  MultipleClips,

  /**
   * Composition-level effects, which have to be rendered.
   */
  CompositionHasEffects,

  /**
   * Track-level effects, which have to be rendered.
   */
  TrackHasEffects,

  /**
   * Clip-level effects, which have to be rendered.
   */
  ClipHasEffects,

  /**
   * [ExportSpec.frameRate] names a rate, which only an encoder can deliver.
   */
  FrameRateSet,

  /**
   * [ExportSpec.videoCodec] names a codec rather than leaving it to the source's own.
   */
  VideoCodecNamed,

  /**
   * [ExportSpec.audioCodec] names a codec rather than leaving it to the source's own.
   */
  AudioCodecNamed,

  /**
   * [ExportSpec.targetHeight] asks for a frame the source is not already at.
   */
  TargetHeightSet,

  /**
   * [ExportSpec.bitrate] names a rate, which only an encoder can deliver.
   */
  BitrateSet,

  /**
   * Geometry moves the frame away from the source's, whether from a target height or an effect.
   */
  FrameResized,

  /**
   * [EditComposition.audio] asks for something other than the source's own audio.
   */
  AudioSpecChanged,

  /**
   * The track is held off until after the composition starts, so the output opens on a gap.
   */
  TrackStartsLate,

  /**
   * The track contributes only audio or only video, so a stream the source carries is dropped.
   */
  TrackDropsAStream,

  /**
   * The clip's audio level is not unity, so the samples have to be scaled.
   */
  ClipGainChanged,

  /**
   * The track's audio level is not unity, so the samples have to be scaled.
   */
  TrackGainChanged,

  /**
   * The composition's audio level is not unity, so the samples have to be scaled.
   */
  CompositionGainChanged,

  /**
   * The clip is trimmed and no sync sample sits within [Clip.snapWithin] of the cut, so reaching it
   * means decoding.
   */
  TrimNotOnSyncSample,

  /**
   * The muxer will not take this source's streams without re-encoding them.
   */
  MuxerRefusesSource,

  /**
   * The source's codecs have no [VideoCodec] or [AudioCodec] member to name them by, so a copy
   * could not report what it wrote.
   */
  SourceCodecUnnameable,

  /**
   * This backend has no stream copy at all.
   */
  BackendCannotCopy,

  /**
   * The grade has to come down to SDR, which only a decode and re-encode can do.
   */
  GradeMustToneMap,
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
 * @property duration How long the file this export writes will run. A cut that moved back to a sync
 *   sample opens earlier than it was asked to, so this is measured from where each clip resolved to
 *   open rather than from where its trim was written. A [ExportPath.Transmux] copy cannot cut a
 *   packet in half, so its file runs out to the end of the last whole packet and can be a frame or
 *   two longer than this.
 * @property copyBlockedBy Why this export re-encodes rather than copying its streams across. Empty
 *   when [path] is [ExportPath.Transmux], and otherwise every term that applied, so a caller can
 *   see which field to drop to get the near-instant path back.
 * @property composition The composition this plan was resolved from, echoed back unchanged so an
 *   engine can negotiate it again. Nothing resolved is folded into it, so read [duration] for the
 *   length this export writes rather than measuring its trims.
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
    public val duration: Duration,
    public val copyBlockedBy: List<CopyBlocker>,
    @property:InternalFilmstripApi public val composition: EditComposition,
    @property:InternalFilmstripApi public val spec: ExportSpec,
  ) {
    override fun toString(): String =
      "ExportPlan(path=$path, output=$output, duration=$duration, effects=${effectOrder.size}, parity=$parity)"
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
