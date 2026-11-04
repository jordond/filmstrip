package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList

/**
 * A facade with only the WebCodecs backend registered, so every plan and export in a test goes
 * through this module.
 */
internal fun webCodecsFilmstrip(): Filmstrip = Filmstrip { webCodecsBackend() }

/**
 * The plan [filmstrip] makes for [composition] under [spec]. A degraded plan is still a plan, and a
 * refusal fails the test with the reasons it gave.
 */
internal suspend fun planOf(
  filmstrip: Filmstrip,
  composition: EditComposition,
  spec: ExportSpec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None),
): ExportPlan =
  when (val verdict = filmstrip.plan(composition, spec)) {
    is Verdict.Capable -> verdict.plan
    is Verdict.Degraded -> verdict.plan
    is Verdict.Incapable -> throw AssertionError("the plan was refused: ${verdict.reasons.map { it.message }}")
  }

/**
 * Plans and exports [composition] through the public facade into [to], and hands back the single
 * success. A failure status fails the test with its message.
 */
internal suspend fun exportOf(
  composition: EditComposition,
  to: MediaSink = MediaSink.Temporary,
  spec: ExportSpec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None),
): ExportStatus.Success {
  val filmstrip = webCodecsFilmstrip()
  val statuses = filmstrip.export(planOf(filmstrip, composition, spec), to).toList()
  val failure = statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()
  if (failure != null) throw AssertionError("the export failed: ${failure.error.message}")
  return withClue("statuses were ${statuses.map { it::class.simpleName }}") {
    (statuses.filterIsInstance<ExportStatus.Success>().singleOrNull()).shouldNotBeNull()
  }
}

/**
 * The file an export wrote to a [MediaSink.Uri], read back as a source.
 */
internal fun outputOf(success: ExportStatus.Success): MediaSource =
  MediaSource.Uri(success.output.shouldBeInstanceOf<MediaSink.Uri>().uri)
