package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration

/**
 * A composition with every decision already made, ready for a platform to build its own graph from.
 *
 * Everything scale-dependent is settled here: the output frame, the frame rate, the codecs and the
 * per-clip effect chains. A platform lowering reads this and never re-decides any of it, which is
 * what keeps two engines from disagreeing about the same edit.
 *
 * @property compositionGeometry Composition-level geometry, applied to [compositionInputSize] before
 *   the frame is pinned to [OutputFormat.size].
 * @property compositionInputSize The frame [compositionGeometry] measures against: the source frame
 *   after every clip and track effect, before geometry pins it to [OutputFormat.size].
 * @property compositionEffects Composition-level effects that run after the frame is pinned, so a
 *   normalized measurement in one is a fraction of the real output frame.
 * @property fit How a frame that does not match the output aspect is fitted to it.
 * @property fill What fills the frame where no clip's pixels land.
 * @property hdr What to do about high dynamic range, after the device has been asked.
 * @property hdrTransfer The transfer function the output is written in when the grade survives to
 *   the encoder, or null for an SDR output or a tone-mapped one.
 * @property path Whether the streams can be copied across without re-encoding. An engine that has
 *   no passthrough of its own ignores it and transcodes.
 */
@InternalFilmstripApi
public class ResolvedComposition(
  public val tracks: List<ResolvedTrack>,
  public val compositionGeometry: List<ResolvedEffect>,
  public val compositionInputSize: Size,
  public val compositionEffects: List<ResolvedEffect>,
  public val output: OutputFormat,
  public val fit: Fit,
  public val fill: Fill,
  public val duration: Duration,
  public val hdr: ResolvedHdr,
  public val hdrTransfer: HdrTransfer?,
  public val path: ExportPath,
  public val audio: AudioSpec,
  public val adjustments: List<Adjustment>,
)

/**
 * Whether the fill can reach the output frame at all.
 *
 * Only [Fit.Contain] leaves bars, and only a track that starts after the composition does leaves a
 * gap. Every other fit covers the frame, so nothing of the fill is ever seen and a backend can skip
 * lowering it.
 */
@InternalFilmstripApi
public val ResolvedComposition.showsFill: Boolean
  get() = fit == Fit.Contain || tracks.any { it.start > Duration.ZERO }

/**
 * Whether the fill has to be painted after composition-level effects have run, rather than
 * underneath the frame before they do.
 *
 * Bars only exist under [Fit.Contain], and only a colour fill is held back there, since a
 * frame-derived fill carries the same grade the frame does. A gap is always a colour, so it is
 * always held back.
 */
@InternalFilmstripApi
public val ResolvedComposition.paintsFillAfterEffects: Boolean
  get() = (fit == Fit.Contain && !fill.derivesFromFrame) || tracks.any { it.start > Duration.ZERO }

/**
 * One track of a [ResolvedComposition].
 */
@InternalFilmstripApi
public class ResolvedTrack(
  public val content: TrackContent,
  public val looping: Boolean,
  public val start: Duration,
  public val clips: List<ResolvedClip>,
) {
  public val duration: Duration get() = clips.fold(start) { total, clip -> total + clip.duration }
}

/**
 * One clip of a [ResolvedTrack], with its trim resolved against the source's real duration.
 *
 * @property gain The audio gain this clip contributes at, with every scope's level already
 *   multiplied in. Zero for silence.
 * @property startsAtKeyFrame Whether this clip's trim is asserted to open on a sync sample, which
 *   is what lets the clipping stream-copy without transcoding.
 */
@InternalFilmstripApi
public class ResolvedClip(
  public val source: MediaSource,
  public val info: MediaInfo,
  public val start: Duration,
  public val end: Duration,
  public val effects: List<ResolvedEffect>,
  public val gain: Float,
  public val startsAtKeyFrame: Boolean,
) {
  public val duration: Duration get() = end - start
}

/**
 * One effect, lowered, still carrying the id it came from.
 *
 * The id travels with the platform object because an engine that cannot use what a resolver handed
 * back has to be able to say which effect it was.
 */
@InternalFilmstripApi
public class ResolvedEffect(
  public val specId: String,
  public val effect: PlatformEffect,
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
