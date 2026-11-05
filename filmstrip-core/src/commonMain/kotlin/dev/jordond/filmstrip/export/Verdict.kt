package dev.jordond.filmstrip.export

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.ParityNote
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration

/**
 * What this device will do with an edit, decided before any decoding, encoding or writing happens.
 *
 * Returned by [dev.jordond.filmstrip.Filmstrip.plan], and the only source of an [ExportPlan].
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
   * @throws IllegalArgumentException when [adjustments] is empty.
   */
  @Poko
  public class Degraded(
    public val plan: ExportPlan,
    public val adjustments: List<Adjustment>,
  ) : Verdict {
    init {
      require(adjustments.isNotEmpty()) { "A degraded verdict needs at least one adjustment." }
    }
  }

  /**
   * The edit cannot run on this device. There is no plan.
   *
   * @property reasons Why it cannot run.
   * @property withoutUnsupported What would run with the impossible effects removed, or null when
   *   nothing would. Its adjustments name each removed effect as [AdjustmentKind.EffectDropped],
   *   alongside anything else the smaller edit changed.
   */
  @Poko
  public class Incapable(
    public val reasons: List<FilmstripError>,
    public val withoutUnsupported: Degraded?,
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
 * Not every kind needs to reach the user. [HdrToneMapped] and [EffectDropped] change how the output looks next to the
 * preview the user just watched, and [ColorSpaceChanged] can on a color-managed player, so surface those.
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
   * grade describes the output. Asking for [HdrMode.ToneMapToSdr] reports nothing here, since that
   * got what it asked for. An engine whose tone curve departs from the one the other backends run
   * reports [EffectApproximated] on every tone map instead, the asked-for one included, and a strict
   * export refuses that like any other fallback.
   */
  HdrToneMapped,

  /**
   * A resolver returned a degraded realisation of an effect, or the engine's tone map runs a curve of its own rather
   * than the shared one. The tone curve case names the [HdrMode] that reached the tone map as what was requested.
   */
  EffectApproximated,

  /**
   * An effect was removed because this device cannot run it.
   *
   * Only carried by [Verdict.Incapable.withoutUnsupported], and by the [ExportStatus.Success] of an
   * export that ran its plan.
   */
  EffectDropped,

  /**
   * A hardware path was unavailable and a slower one was substituted.
   */
  SoftwareFallback,

  /**
   * The file records a different color space than was asked for, because the encoder was measured converting into
   * that one when asked for the requested one.
   *
   * The colors decode as authored through the recorded matrix. A color-managed player can still show them a shade
   * apart where the recorded primaries differ from the requested ones.
   */
  ColorSpaceChanged,
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
   * More than one track, whose audio has to be mixed.
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
   * [ExportSpec.heightPx] names a height, which only an encoder can deliver.
   */
  HeightSet,

  /**
   * [ExportSpec.bitrate] names a rate, which only an encoder can deliver.
   */
  BitrateSet,

  /**
   * Geometry moves the frame away from the source's, whether from a target height or an effect.
   */
  FrameResized,

  /**
   * [EditComposition.audio] leaves a stream the source carries out of the file, the way
   * [dev.jordond.filmstrip.edit.AudioSpec.Remove] and [dev.jordond.filmstrip.edit.AudioSpec.AudioOnly] do.
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
   * The composition's audio level is not unity, so the samples have to be scaled. That is
   * [dev.jordond.filmstrip.edit.AudioSpec.Mute], or a volume other than one.
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
 * One part of an edit that renders nothing, which filmstrip left out of the export rather than run.
 *
 * The output is exactly what the caller asked for, so a notice is not an [Adjustment], does not change the
 * [Verdict] and gives [ExportSpec.strict] nothing to refuse. It is there so an edit that looks like it should
 * change the frame or the audio can say why it did not. Leaving it out also means it costs nothing, so a clip
 * whose only effects are inert can still copy its streams across.
 *
 * @property subject What was left out: an effect id, `fill`, `fadeIn` or `fadeOut`.
 * @property reason Why it renders nothing.
 * @property message A human-readable description naming where in the edit it sits.
 */
@Poko
public class InertNotice(
  public val subject: String,
  public val reason: InertReason,
  public val message: String,
)

/**
 * Why an [InertNotice] renders nothing.
 *
 * More members may arrive as filmstrip learns to spot more of these, so a consumer must handle one it does not
 * recognise.
 */
public enum class InertReason {
  /**
   * The effect leaves every pixel as it was, like a brightness of one, a crop to the aspect the frame already has,
   * a whole turn, or a fill no frame ever shows.
   */
  NoChangeToFrame,

  /**
   * The overlay is never drawn, because its opacity is zero, its text is empty or its window falls outside the run it
   * is drawn over.
   */
  NeverVisible,

  /**
   * The fade has no audio to apply to, because the output keeps no audio, the composition is muted, or the track or
   * clip carries none.
   */
  NoStreamToApplyTo,
}

/**
 * What an export will actually do, resolved before it starts.
 *
 * Obtainable only from [Verdict.Capable], [Verdict.Degraded], or the
 * [Verdict.Incapable.withoutUnsupported] fallback of an edit carrying effects this device cannot run.
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
 *   open rather than from where its trim was written. An [ExportPath.Copy] export cannot cut a
 *   packet in half, so its file runs out to the end of the last whole packet and can be a frame or
 *   two longer than this.
 * @property copyBlockedBy Why this export re-encodes rather than copying its streams across. Empty
 *   when [path] is [ExportPath.Copy], and otherwise every term that applied, so a caller can
 *   see which field to drop to get the near-instant path back.
 * @property inertNotices Every part of the edit that renders nothing and so was left out of the export. The
 *   [composition] still carries each of them as written.
 * @property composition The composition this plan was resolved from, so an engine can negotiate it
 *   again. A [Verdict.Incapable.withoutUnsupported] plan carries it with the effects this device
 *   cannot run removed, and every other plan echoes it back unchanged. Nothing resolved is folded
 *   into it, so read [duration] for the length this export writes rather than measuring its trims.
 * @property spec The spec this plan was resolved from.
 * @param droppedEffects One [AdjustmentKind.EffectDropped] per effect taken out of [composition],
 *   which the export's [ExportStatus.Success] reports alongside what the engine changed.
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
    public val inertNotices: List<InertNotice> = emptyList(),
    @property:InternalFilmstripApi public val composition: EditComposition,
    @property:InternalFilmstripApi public val spec: ExportSpec,
    internal val droppedEffects: List<Adjustment> = emptyList(),
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
 * @property frameRate The frame rate the export writes, in frames per second. The planner resolves it from the spec,
 * the source or a default, so it is always above zero.
 * @property audioFormat The audio format the whole composition is normalised to, or null when there
 *   is no audio.
 */
@Poko
public class OutputFormat(
  public val size: Size,
  public val videoCodec: VideoCodec,
  public val audioCodec: AudioCodec,
  public val bitrate: Bitrate?,
  public val frameRate: Int,
  public val audioFormat: AudioFormat?,
) {
  init {
    require(frameRate > 0) { "An output frame rate must be above zero, got $frameRate." }
  }
}

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
  Copy,

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
 * @property isCopy True when nothing is re-encoded.
 */
@Poko
public class ExportEstimate(
  public val outputSizeBytesMin: Long?,
  public val outputSizeBytesMax: Long?,
  public val approximateDuration: Duration?,
  public val isCopy: Boolean,
)
