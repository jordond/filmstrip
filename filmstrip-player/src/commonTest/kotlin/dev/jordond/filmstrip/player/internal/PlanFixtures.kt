package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import kotlinx.coroutines.Job
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object PlanFixtures {
  const val BROKEN = "the prober threw"
  val KEEP = ExportSpec(hdr = HdrMode.KeepHdr)
  val BITRATE = ExportSpec(bitrate = Bitrate.mbps(8))
  val CAPPED = PreviewQualityPolicy.CapHeight(360)
  val RECAPPED = PreviewQualityPolicy.CapHeight(480)
  val PLAN_LENGTH: Duration = 3.seconds
  val FRAME = Size(1280, 720)

  fun resolvedAt(hdr: ResolvedHdr): ResolvedComposition =
    ResolvedComposition(
      tracks = emptyList(),
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = FRAME,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      layoutSize = FRAME,
      fill = Fill.Solid(),
      duration = PLAN_LENGTH,
      hdr = hdr,
      hdrTransfer = null,
      path = ExportPath.Transcode,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
    )

  val EDIT =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.of("planned.mp4"), TimeRange.of(Duration.ZERO, 2.seconds))))),
    )
  val PARAMETERS_EDIT = EditComposition(tracks = EDIT.tracks, fill = Fill.Blurred())
  val OTHER_EDIT =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.of("other.mp4"), TimeRange.of(Duration.ZERO, 2.seconds))))),
    )
  val PLAN =
    PreviewPlan(
      resolved = resolvedAt(ResolvedHdr.ToneMap),
      info = PreviewInfo(FRAME, 1f, EffectParity.Exact, emptyList(), emptyList()),
      spec = ExportSpec(),
      layoutSize = null,
    )

  // The same plan at the kept grade.
  val KEEP_PLAN =
    PreviewPlan(
      resolved = resolvedAt(ResolvedHdr.Keep),
      info = PLAN.info,
      spec = ExportSpec(),
      layoutSize = null,
    )
}

// Records every edit, policy and spec it is asked to plan, and answers each with [answer].
internal class RecordingPlanner : PreviewPlanner<PreviewPlan> {
  val asked: MutableList<Pair<EditComposition, PreviewQualityPolicy>> = mutableListOf()
  val specs: MutableList<ExportSpec> = mutableListOf()
  var gate: Job? = null
  var answer: () -> PlanResult<PreviewPlan, PlaybackError> = { PlanResult.Ready(PlanFixtures.PLAN) }

  override suspend fun plan(
    composition: EditComposition,
    policy: PreviewQualityPolicy,
    exportSpec: ExportSpec,
  ): PlanResult<PreviewPlan, PlaybackError> {
    asked += composition to policy
    specs += exportSpec
    gate?.join()
    return answer()
  }
}
