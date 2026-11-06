package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.transform.internal.ExportPlanner
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import kotlin.time.Duration

/**
 * An adjustment the planner made, and the refusal it becomes when the spec is
 * [ExportSpec.strict].
 */
internal class Concession(
  val adjustment: Adjustment,
  val strictRefusal: FilmstripError,
)

/**
 * The codec an export encodes with, and the fallback it took to get there when it is not the one
 * asked for.
 */
internal class CodecChoice<C>(
  val codec: C,
  val fallback: Concession?,
)

/**
 * What one negotiation is asked against, the same for the first attempt and for the retry that
 * drops the effects the device cannot run.
 *
 * An engine builds one from what it read about the sources and the device, and hands it to [ExportPlanner.negotiate]
 * through its own planner.
 *
 * @property spec What the export is asked for.
 * @property device What this device's encoders can do.
 * @property infos What each source the composition names turned out to be.
 * @property openings Where a stream copy of each source could open, which is the sync sample at or before that clip's
 * cut. A source with no entry names none, and a trim over it blocks the copy.
 * @property layoutSize The output frame text is laid out against, for a caller planning a frame smaller than the one an
 * export writes. Null lays text out against the frame the negotiation settles on, which is what an export does.
 */
@InternalFilmstripApi
public class Ask(
  public val spec: ExportSpec,
  public val device: DeviceCapabilities,
  public val infos: Map<MediaSource, MediaInfo>,
  public val openings: Map<MediaSource, Duration> = emptyMap(),
  public val layoutSize: Size? = null,
)

/**
 * The frame the output is measured from and the size the composition asks for.
 *
 * @property firstClip The primary track's first clip, the only one a copy could ever cover.
 * @property firstVideo The video the output frame and cadence are measured from.
 * @property compositionInputSize The frame composition geometry measures against, after every clip
 *   and track effect of the clip framing the output.
 * @property naturalSize The frame the composition writes with no height asked of it and before any encoder ceiling.
 * @property heightPx The height the spec moves [naturalSize] to, or null when it keeps it, as
 *   [dev.jordond.filmstrip.geometry.effectiveHeight] reads it.
 * @property requestedSize The frame the composition asks for, before any encoder ceiling.
 */
internal class Framing(
  val firstClip: Clip,
  val firstVideo: VideoTrackInfo,
  val framedByStill: Boolean,
  val compositionGeometry: List<EffectSpec>,
  val compositionInputSize: Size,
  val naturalSize: Size,
  val heightPx: Int?,
  val requestedSize: Size,
)

/**
 * Whether the streams copy across and what happens to an HDR grade.
 *
 * @property encodesHdr Whether the grade reaches an encoder, which is never true on a copy.
 * @property copyOpensAt Where the copy opens, or null when the export transcodes.
 */
internal class Grade(
  val sourceIsHdr: Boolean,
  val gradesAgree: Boolean,
  val hdr: ResolvedHdr,
  val hdrTransfer: HdrTransfer?,
  val encodesHdr: Boolean,
  val path: ExportPath,
  val copyBlockedBy: List<CopyBlocker>,
  val copyOpensAt: Duration?,
)

/**
 * The codec, the encoder, and the frame and rate it is held to.
 *
 * @property ceiling The largest frame the encoder takes, with a still's bound folded in.
 * @property overCeiling Whether the requested frame sat above [ceiling] rather than only off the
 *   encoder's alignment.
 * @property bitrate The video bitrate the encoder is handed, or null on a copy.
 * @property bitrateClamp The concession made when the bitrate sat above the encoder's ceiling.
 */
internal class Encoding(
  val video: CodecChoice<VideoCodec>,
  val encoder: VideoEncoderCapability?,
  val ceiling: Size?,
  val overCeiling: Boolean,
  val outputSize: Size,
  val layoutFrame: Size,
  val target: LoweringTarget,
  val bitrate: Bitrate?,
  val bitrateClamp: Concession?,
)

/**
 * Every track laid onto the composition timeline, with every effect its passes lowered.
 */
internal class Timeline(
  val duration: Duration,
  val tracks: List<ResolvedTrack>,
  val lowered: List<LoweredEffect>,
) {
  val span: TimeRange = TimeRange.of(Duration.ZERO, duration)
}

/**
 * What one phase of a negotiation settled on, or why it refused.
 */
internal sealed interface Phase<out T> {
  class Next<T>(
    val value: T,
  ) : Phase<T>

  class Refused(
    val error: FilmstripError,
  ) : Phase<Nothing>
}

/**
 * The value this phase settled on, or whatever [refuse] does with its refusal.
 */
internal inline fun <T> Phase<T>.orElse(refuse: (FilmstripError) -> Nothing): T =
  when (this) {
    is Phase.Next -> value
    is Phase.Refused -> refuse(error)
  }
