package dev.jordond.filmstrip.transform.internal.resolve

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.copy.CopyPrefix
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf
import dev.jordond.filmstrip.transform.internal.geometry.ClipPlacement
import dev.jordond.filmstrip.transform.internal.geometry.derivesFromFrame
import kotlin.time.Duration

/**
 * A composition with every decision already made, ready for a platform to build its own graph from.
 *
 * Everything scale-dependent is settled here: the output frame, the frame rate, the codecs and the
 * per-clip effect chains. A platform lowering reads this and never re-decides any of it, which is
 * what keeps two engines from disagreeing about the same edit.
 *
 * @property compositionEffects Composition-level effects that run after the frame is pinned, so a
 *   normalized measurement in one is a fraction of the real output frame.
 * @property layoutSize The output frame text is laid out against. [OutputFormat.size] for an
 *   export, and the frame an export would write for a graph lowered smaller than that, such as a
 *   preview under a quality cap. A backend hands this to a resolver as it is.
 * @property fill What fills the frame where no clip's pixels land.
 * @property hdr What to do about high dynamic range, after the device has been asked.
 * @property hdrTransfer The transfer function the output is written in when the grade survives to
 *   the encoder, or null for an SDR output or a tone-mapped one.
 * @property path Whether the streams can be copied across without re-encoding. An engine that has
 *   no copy path of its own ignores it and transcodes.
 * @property encoderName The encoder the device resolved for [OutputFormat.videoCodec], or null when
 *   the backend does not name encoders. An engine that spells an encoder out invokes this one, so
 *   what it runs is what `capabilities()` reported.
 * @property naturalSize The frame the composition writes with no height asked of it and before any encoder ceiling,
 *   which is [OutputFormat.size] for a composition built at its own size. It stays the same at every height the edit
 *   is lowered at, so every clip's [ResolvedClip.placement] is measured against it.
 * @property copyPrefix The video packets a copy that ends before its source does writes, from [copyPrefixOf] over the
 *   packets the engine read into [dev.jordond.filmstrip.transform.internal.negotiation.Ask.copyPackets]. Null for
 *   every other plan, which writes every packet its window holds.
 * @property mixAudioEffects The composition's audio effects, in the order they run on the finished mix, after every
 *   clip's [ResolvedClip.gain].
 */
@InternalFilmstripApi
public class ResolvedComposition(
  public val tracks: List<ResolvedTrack>,
  public val compositionEffects: List<ResolvedEffect>,
  public val output: OutputFormat,
  public val layoutSize: Size,
  public val fill: Fill,
  public val duration: Duration,
  public val hdr: ResolvedHdr,
  public val hdrTransfer: HdrTransfer?,
  public val path: ExportPath,
  public val audio: AudioSpec,
  public val adjustments: List<Adjustment>,
  public val encoderName: String? = null,
  public val naturalSize: Size = output.size,
  public val copyPrefix: CopyPrefix.Packets? = null,
  public val mixAudioEffects: List<ResolvedAudioEffect> = emptyList(),
)

/**
 * Whether a graph standing for this plan draws what [other] asks of it and keeps the audio [other] keeps.
 *
 * Reads the frame size, the frame rate, what the plan settled on for high dynamic range and whether any audio survives.
 * The bitrate, the codecs and the audio format are left out, so a copy and a transcode of one frame compare as equal.
 * A backend's parameter swap calls this first and adds only the limits of its own graph.
 */
@InternalFilmstripApi
public fun ResolvedComposition.drawsSameFormatAs(other: ResolvedComposition): Boolean =
  output.size == other.output.size &&
    output.frameRate == other.output.frameRate &&
    hdr == other.hdr &&
    hdrTransfer == other.hdrTransfer &&
    keepsAudio == other.keepsAudio

private val ResolvedComposition.keepsAudio: Boolean
  get() = output.audioCodec != AudioCodec.None

/**
 * Whether the fill can reach the output frame at all.
 *
 * Only a clip whose placement leaves bars, a clip with transparent pixels or a gap in the video lets the fill show.
 * When none of them happens nothing of the fill is ever seen and a backend can skip lowering it.
 */
@InternalFilmstripApi
public val ResolvedComposition.showsFill: Boolean
  get() = anyClipExposesFill || laysGaps

// Whether the video has a gap anywhere: a track that starts after the composition does, or a clip with no picture of
// its own on a track that carries video.
private val ResolvedComposition.laysGaps: Boolean
  get() =
    tracks.any { track ->
      track.start > Duration.ZERO || (track.content != TrackContent.Audio && track.clips.any { it.info.video == null })
    }

/**
 * Whether any clip on a track that draws video leaves bars for the fill to show through.
 *
 * A clip on an audio track never draws its picture, so its placement leaves nothing for the fill.
 */
@InternalFilmstripApi
public val ResolvedComposition.anyClipLeavesBars: Boolean
  get() = tracks.any { track -> track.content != TrackContent.Audio && track.clips.any { it.placement.leavesBars } }

/**
 * Whether any clip on a track that draws video carries transparent pixels, which the fill shows through.
 *
 * Read off [dev.jordond.filmstrip.media.VideoTrackInfo.hasAlpha], so a source whose alpha a backend cannot decode
 * never counts.
 */
@InternalFilmstripApi
public val ResolvedComposition.anyClipHasAlpha: Boolean
  get() =
    tracks.any { track ->
      track.content != TrackContent.Audio &&
        track.clips.any { it.info.video?.hasAlpha == true }
    }

/**
 * Whether any clip lets the fill show through, either by leaving bars or by carrying transparent pixels.
 *
 * Alpha shows the fill even under a clip that covers the whole frame, so a blurred fill is built for it as well.
 */
@InternalFilmstripApi
public val ResolvedComposition.anyClipExposesFill: Boolean
  get() = anyClipLeavesBars || anyClipHasAlpha

/**
 * Whether the fill has to be painted after composition-level effects have run, rather than
 * underneath the frame before they do.
 *
 * Only a color fill is held back behind a clip's bars or alpha, since a frame-derived fill carries the same
 * grade the frame does. A gap is always a color, so it is always held back.
 */
@InternalFilmstripApi
public val ResolvedComposition.paintsFillAfterEffects: Boolean
  get() = (anyClipExposesFill && !fill.derivesFromFrame) || laysGaps

/**
 * One track of a [ResolvedComposition].
 *
 * @property clips Every pass this track lays, in the order it lays them. A looping track repeats its
 *   run from the top until the composition ends, with the last pass cut where it runs past, so a
 *   backend lays exactly what is here and works out no repeat of its own.
 */
@InternalFilmstripApi
public class ResolvedTrack(
  public val content: TrackContent,
  public val looping: Boolean,
  public val start: Duration,
  public val clips: List<ResolvedClip>,
) {
  public val duration: Duration get() = clips.fold(start) { total, clip -> total + clip.outputDuration }
}

/**
 * One clip of a [ResolvedTrack], with its trim resolved against the source's real duration.
 *
 * @property gain The audio gain this clip contributes at, in the clip's own time, with every
 *   scope's level already multiplied in. A flat curve at zero is silence, and a backend that can
 *   only scale by one number reads [ResolvedGain.constant].
 * @property startsAtKeyFrame Whether this clip's trim is asserted to open on a sync sample, which
 *   is what lets the clipping stream-copy without transcoding.
 * @property span This clip's slot on the composition timeline, counted from the start of the whole
 *   composition. Derived once by the planner, since every backend lays its clips end to end from
 *   the track's own start and an effect that reads the time has to be measured against the same run
 *   on all of them.
 * @property sourceIndex Which clip of the track this was laid from. A looping track lays the same
 *   clip down more than once, so several resolved clips can name one.
 * @property placement Where this clip's pixels land in the output frame, under its own fit.
 * @property canvasEffectIndices The index in [effects] of the crop that makes each of [ClipPlacement.canvases], in the
 * same order. A backend builds each canvas right after its crop, before the effects after it run.
 * @property speed How fast this clip plays [start] to [end] through [span]. A backend reads [sourceDuration] for what
 * it decodes and [outputDuration] for what it lays.
 * @property audioTrack The one audio track of the source this clip plays, or null when the source has none. A backend
 * reads this and leaves every other audio track of the source out. Left unset, it is the source's default track.
 * @property audioEffects The clip's audio effects and then its track's, in the order they run on this clip's audio,
 * after its [speed] and before its [gain]. Empty for a clip nothing of which is heard.
 */
@InternalFilmstripApi
public class ResolvedClip(
  public val source: MediaSource,
  public val info: MediaInfo,
  public val start: Duration,
  public val end: Duration,
  public val effects: List<ResolvedEffect>,
  public val gain: ResolvedGain,
  public val startsAtKeyFrame: Boolean,
  public val span: TimeRange,
  public val sourceIndex: Int,
  public val placement: ClipPlacement = ClipPlacement.Filling,
  public val canvasEffectIndices: List<Int> = emptyList(),
  public val speed: Double = 1.0,
  public val audioTrack: ResolvedAudioTrack? = resolvedAudioTrackOf(info.audio, info),
  public val audioEffects: List<ResolvedAudioEffect> = emptyList(),
) {
  init {
    require(canvasEffectIndices.size == placement.canvases.size) {
      "A clip builds ${placement.canvases.size} canvases but names a crop for ${canvasEffectIndices.size} of them."
    }
  }

  /**
   * How much source this clip plays, from [start] to [end].
   */
  public val sourceDuration: Duration get() = end - start

  /**
   * How long this clip runs on the composition timeline, which is the length of [span].
   */
  public val outputDuration: Duration get() = checkNotNull(span.duration) { "A laid clip's span has no end." }

  /**
   * The source time this clip shows at [compositionTime], read from where its [span] opens.
   */
  public fun sourceTimeAt(compositionTime: Duration): Duration =
    start + sourceOffsetAt(compositionTime - span.start, speed)
}

/**
 * Where the frame this clip's effects leave is drawn in the output frame, as a fraction of it.
 *
 * Every canvas the clip builds is already inside that frame, and a canvas nothing reads is folded into the rect.
 */
@InternalFilmstripApi
public val ResolvedClip.drawnRect: NormalizedRect
  get() = placement.drawnRect

/**
 * One effect, lowered, still carrying the id it came from.
 *
 * The id travels with the platform object because an engine that cannot use what a resolver handed
 * back has to be able to say which effect it was. A folded run of color effects carries every id
 * the caller wrote into it, comma separated, rather than the one the fold made up.
 *
 * @property stage The pipeline stage the effect runs in. A folded run takes the stage of the effects
 *   it was folded from, which always share one.
 */
@InternalFilmstripApi
public class ResolvedEffect(
  public val specId: String,
  public val effect: PlatformEffect,
  public val stage: EffectStage,
)

/**
 * What the plan settled on for high dynamic range, once the device had been asked.
 */
@InternalFilmstripApi
public enum class ResolvedHdr {
  /**
   * Encode HDR input as HDR.
   */
  Keep,

  /**
   * Tone-map to SDR.
   */
  ToneMap,
}
