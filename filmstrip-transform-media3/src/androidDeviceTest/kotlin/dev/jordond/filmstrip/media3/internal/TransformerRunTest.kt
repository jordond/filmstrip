package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.media3.Media3ExportProbe
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One media3 export driven start to finish, and what it reports along the way.
 *
 * The statuses are collected off the test scheduler, whose virtual clock would spin the progress poll instead of
 * pacing it.
 *
 * Camel case test names, because dex below version 040, which needs API 30, rejects a space in a method name.
 */
class TransformerRunTest {
  private val probe = Media3ExportProbe()
  private val context = probe.context

  @Test
  fun aFinishedExportReportsTheProbedFileAndThePlansAdjustments() =
    runTest(timeout = TIMEOUT) {
      val prober = RecordingProber(ProbeResult.Success(INFO))
      val output = output("finished.mp4")

      val statuses = statusesOf(run(probe.fixtureFile(Media3ExportProbe.CLIP), output, prober))

      statuses.first() shouldBe ExportStatus.Started
      val success = statuses.last().shouldBeInstanceOf<ExportStatus.Success>()
      success.output shouldBe MediaSink.Path(output.path)
      success.info shouldBeSameInstanceAs INFO
      withClue("the plan's own adjustment was dropped: ${success.adjustments}") {
        (PLANNED in success.adjustments) shouldBe true
      }
      prober.asked shouldBe listOf(MediaSource.Path(output.path))
      withClue("nothing was written to ${output.path}") { (output.length() > 0) shouldBe true }
    }

  @Test
  fun progressClimbsAndPlacesItselfOnTheComposition() =
    runTest(timeout = TIMEOUT) {
      val statuses =
        statusesOf(run(probe.fixtureFile(LONG_CLIP), output("progress.mp4"), duration = LONG, scaled = true))

      val fractions = statuses.filterIsInstance<ExportStatus.Progress>()
      withClue("a twelve second export reported no progress") { fractions.isNotEmpty() shouldBe true }
      fractions.zipWithNext().forEach { (before, after) ->
        withClue("progress fell from ${before.fraction} to ${after.fraction}") {
          (after.fraction > before.fraction) shouldBe true
        }
      }
      fractions.forEach {
        withClue("a fraction of ${it.fraction}") { (it.fraction > 0f && it.fraction <= 1f) shouldBe true }
        it.position shouldBe LONG * it.fraction.toDouble()
      }
      statuses.last().shouldBeInstanceOf<ExportStatus.Success>()
    }

  @Test
  fun aWrittenFileThatCannotBeReadBackIsAFailure() =
    runTest(timeout = TIMEOUT) {
      val unreadable = ProbeResult.Failure(FilmstripError.SourceUnreadable("probe", "nothing there"))
      val output = output("unreadable.mp4")

      val statuses = statusesOf(run(probe.fixtureFile(Media3ExportProbe.CLIP), output, RecordingProber(unreadable)))

      val error =
        statuses
          .last()
          .shouldBeInstanceOf<ExportStatus.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
      error.sink shouldBe output.path
    }

  @Test
  fun aSourceMedia3CannotOpenFailsAndLeavesNoFileBehind() =
    runTest(timeout = TIMEOUT) {
      val output = output("missing-source.mp4")

      val statuses = statusesOf(run(File(context.cacheDir, "does-not-exist.mp4"), output))

      statuses.first() shouldBe ExportStatus.Started
      statuses.last().shouldBeInstanceOf<ExportStatus.Failure>()
      withClue("a failed export left ${output.path}") { output.exists() shouldBe false }
    }

  @Test
  fun aCollectorThatStopsEarlyCancelsTheExportAndDiscardsTheFile() =
    runTest(timeout = TIMEOUT) {
      val output = output("abandoned.mp4")
      val run = run(probe.fixtureFile(LONG_CLIP), output, duration = LONG, scaled = true)

      var writing = false
      val stoppedOn =
        withContext(Dispatchers.Default) {
          run.run().first { status -> (status is ExportStatus.Progress).also { if (it) writing = output.exists() } }
        }

      stoppedOn.shouldBeInstanceOf<ExportStatus.Progress>()
      withClue("the export finished before it could be abandoned") { (stoppedOn.fraction < 1f) shouldBe true }
      withClue("media3 had not opened ${output.path} by the first progress report") { writing shouldBe true }
      withClue("an abandoned export left ${output.path}") { output.exists() shouldBe false }
    }

  private suspend fun statusesOf(run: TransformerRun): List<ExportStatus> =
    withContext(Dispatchers.Default) { run.run().toList() }

  private fun run(
    source: File,
    output: File,
    prober: MediaProber = RecordingProber(ProbeResult.Success(INFO)),
    duration: Duration = CLIP_LENGTH,
    scaled: Boolean = false,
  ): TransformerRun =
    TransformerRun(
      context = context,
      prober = prober,
      resolved = resolved(duration),
      composition = compositionOf(source, scaled),
      destination = Media3Destination(output.path, context, target = null, reported = MediaSink.Path(output.path)),
    )

  private fun output(name: String): File = File(context.cacheDir, "transformer-run-$name").apply { delete() }

  // A clip media3 is free to copy finishes before the first progress poll, so a run that has to report progress is
  // scaled, which makes media3 decode and encode every frame.
  private fun compositionOf(
    source: File,
    scaled: Boolean,
  ): Composition {
    val effects =
      if (scaled) {
        Effects(
          emptyList(),
          listOf(Presentation.createForHeight(SCALED_HEIGHT)),
        )
      } else {
        Effects.EMPTY
      }
    val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source))).setEffects(effects).build()
    val sequence =
      EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)).addItem(item).build()
    return Composition.Builder(sequence).build()
  }

  // Only what the run reads off a plan is meaningful here. The composition it drives is built above.
  private fun resolved(duration: Duration): ResolvedComposition =
    ResolvedComposition(
      tracks = emptyList(),
      compositionGeometry = emptyList(),
      compositionInputSize = SIZE,
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = SIZE,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      layoutSize = SIZE,
      fit = Fit.Contain,
      fill = Fill.Black,
      duration = duration,
      hdr = ResolvedHdr.ToneMap,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = listOf(PLANNED),
      path = ExportPath.Transcode,
    )

  private class RecordingProber(
    private val answer: ProbeResult,
  ) : MediaProber {
    val asked = mutableListOf<MediaSource>()

    override suspend fun probe(source: MediaSource): ProbeResult = answer.also { asked += source }
  }

  private companion object {
    const val LONG_CLIP = "android_export_long.mp4"
    const val SCALED_HEIGHT = 360
    val CLIP_LENGTH = 2.seconds
    val LONG = 12.seconds
    val SIZE = Size(640, 360)
    val INFO = imageMediaInfoOf(SIZE, EXIF_ORIENTATION_NORMAL, "mp4", CLIP_LENGTH)
    val PLANNED =
      Adjustment(
        kind = AdjustmentKind.BitrateClamped,
        requested = "20 Mbps",
        resolved = "8 Mbps",
        message = "The plan clamped the bitrate.",
      )
  }
}
