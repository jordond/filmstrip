package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.time.Duration

/**
 * What [ExportPlanner.negotiate] settled on: the verdict a caller sees, and the negotiated
 * composition when it is capable.
 */
@InternalFilmstripApi
public class NegotiatedExport(
  public val verdict: Verdict,
  public val composition: NegotiatedComposition?,
)

/**
 * A composition with every decision already made, engine-agnostic: the output frame, the frame
 * rate, the codecs, the HDR mode and the per-clip effect chains, resolved against one device's
 * capabilities.
 *
 * Nothing here is media3's or AVFoundation's. An engine lowers this into whatever its own pipeline
 * consumes; [toResolvedComposition] is the lowering media3 and AVFoundation share, since neither
 * needs to change the effect chain to run it.
 *
 * @property compositionGeometry Composition-level geometry, applied to [compositionInputSize] before
 *   the frame is pinned to [OutputFormat.size].
 * @property compositionInputSize The frame [compositionGeometry] measures against: the source frame
 *   after every clip and track effect, before geometry pins it to [OutputFormat.size].
 * @property compositionEffects Composition-level effects that run after the frame is pinned, so a
 *   normalised measurement in one is a fraction of the real output frame.
 * @property layoutSize The output frame text is laid out against. [OutputFormat.size] for an
 *   export, and the frame an export would write for a caller that negotiated a smaller one, such as
 *   a preview under a quality cap.
 * @property fit How a frame that does not match the output aspect is fitted to it.
 * @property fill What fills the frame where no clip's pixels land.
 * @property hdr What to do about high dynamic range, after the device has been asked.
 * @property hdrTransfer The transfer function the output is written in when the grade survives to
 *   the encoder, or null for an SDR output or a tone-mapped one.
 * @property path Whether the streams can be copied across without re-encoding. An engine that has
 *   no passthrough of its own ignores it and transcodes.
 * @property encoderName The encoder the device resolved for [OutputFormat.videoCodec], or null when
 *   the backend does not name encoders. An engine that spells an encoder out invokes this one, so
 *   what it runs is what `capabilities()` reported.
 */
@InternalFilmstripApi
public class NegotiatedComposition(
  public val tracks: List<ResolvedTrack>,
  public val compositionGeometry: List<ResolvedEffect>,
  public val compositionInputSize: Size,
  public val compositionEffects: List<ResolvedEffect>,
  public val output: OutputFormat,
  public val layoutSize: Size,
  public val fit: Fit,
  public val fill: Fill,
  public val duration: Duration,
  public val hdr: ResolvedHdr,
  public val hdrTransfer: HdrTransfer?,
  public val path: ExportPath,
  public val audio: AudioSpec,
  public val adjustments: List<Adjustment>,
  public val encoderName: String?,
)

/**
 * Whether the fill can reach the output frame at all.
 *
 * Only [Fit.Contain] leaves bars, and only a track that starts after the composition does leaves a
 * gap. Every other fit covers the frame, so nothing of the fill is ever seen and a backend can skip
 * lowering it.
 */
@InternalFilmstripApi
public val NegotiatedComposition.showsFill: Boolean
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
public val NegotiatedComposition.paintsFillAfterEffects: Boolean
  get() = (fit == Fit.Contain && !fill.derivesFromFrame) || tracks.any { it.start > Duration.ZERO }

/**
 * The media3/AVFoundation lowering of a [NegotiatedComposition].
 *
 * An identity copy today, because both pipelines consume the same platform effect chain a resolver
 * already produced. A pipeline that cannot draw from a chain of platform effect objects, such as a
 * single-pass renderer, writes its own lowering instead of using this one.
 */
@InternalFilmstripApi
public fun NegotiatedComposition.toResolvedComposition(): ResolvedComposition =
  ResolvedComposition(
    tracks = tracks,
    compositionGeometry = compositionGeometry,
    compositionInputSize = compositionInputSize,
    compositionEffects = compositionEffects,
    output = output,
    layoutSize = layoutSize,
    fit = fit,
    fill = fill,
    duration = duration,
    hdr = hdr,
    hdrTransfer = hdrTransfer,
    path = path,
    audio = audio,
    adjustments = adjustments,
  )
