package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegProber
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.process.PROBE_DEADLINE_BASE
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.probeExpiredReason
import dev.jordond.filmstrip.ffmpeg.support.hostFfprobe
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaProberFactory
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Plans and exports through the engine with a stand-in ffprobe that hangs on one kind of read and hands every other
 * to the host's, so each read the engine makes past its deadline is shown to refuse the edit with that read's reason.
 *
 * Everything before the read under test runs under the real deadline. The deadline drops to [SHORT] only for it.
 */
class FfmpegProbeDeadlineMediaTest {
  private val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  private val landscape = File(fixtures, "export_landscape.mp4")
  private val source = MediaSource.of(landscape.absolutePath)
  private val directory = Files.createTempDirectory("filmstrip-engine-deadline").toFile()
  private var deadline: Duration = PROBE_DEADLINE_BASE
  private val expired get() = probeExpiredReason("ffprobe", SHORT)

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  @Test
  fun `an opening read past its deadline refuses the edit`() =
    runTest(timeout = TEST_TIMEOUT) {
      if (!landscape.isFile) return@runTest
      val engine = engine(hangsOn = "-skip_frame")
      engine.resolve(composition(Clip(source)), ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>()

      deadline = SHORT
      val trimmed = composition(Clip(source, TimeRange.of(1.seconds, 2.seconds)))
      val refused = engine.resolve(trimmed, ExportSpec()).shouldBeInstanceOf<ResolveResult.Refused>()

      refused.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldBe expired
    }

  // A registered prober answers for the source, so the colour read is the only thing that reaches ffprobe.
  @Test
  fun `a colour read past its deadline refuses the edit`() =
    runTest(timeout = TEST_TIMEOUT) {
      if (!landscape.isFile) return@runTest
      val prober = FfmpegProber(FfmpegRuntime.unshared(FfmpegConfig()))
      val components = ComponentRegistry.Builder().addMediaProberFactory(MediaProberFactory { prober }).build()
      val engine = engine(hangsOn = "-show_format", components)
      engine.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>()

      deadline = SHORT
      val refused = engine.resolve(composition(Clip(source)), ExportSpec()).shouldBeInstanceOf<ResolveResult.Refused>()

      refused.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldBe expired
    }

  @Test
  fun `a read of the written file past its deadline fails the export as unwritable`() =
    runTest(timeout = TEST_TIMEOUT) {
      if (!landscape.isFile) return@runTest
      val output = File(directory, "$WRITTEN.mp4")
      val engine = engine(hangsOn = WRITTEN)
      val composition = composition(Clip(source, TimeRange.of(Duration.ZERO, 1.seconds)))
      val plan = engine.plan(composition, ExportSpec()).shouldBeInstanceOf<Verdict.Capable>().plan

      deadline = SHORT
      val status = engine.export(plan, MediaSink.Path(output.path)).last()

      val error = status.shouldBeInstanceOf<ExportStatus.Failure>().reasons.single()
      error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>().message shouldBe expired
    }

  private fun engine(
    hangsOn: String,
    components: ComponentRegistry = ComponentRegistry.Builder().build(),
  ): FfmpegExportEngine {
    val ffprobe =
      File(directory, "ffprobe").apply {
        writeText("#!/bin/sh\ncase \"$*\" in *$hangsOn*) exec sleep 300 ;; *) exec '$hostFfprobe' \"$@\" ;; esac\n")
        setExecutable(true)
      }
    val runtime =
      FfmpegRuntime.unshared(
        FfmpegConfig(probePath = ffprobe.path),
        deadline = { deadline },
        runner = ProcessRunner(GRACE),
      )
    return FfmpegExportEngine(components, runtime)
  }

  private fun composition(clip: Clip): EditComposition = EditComposition(listOf(Track(listOf(clip))))

  private companion object {
    const val WRITTEN = "deadline-written"
    val SHORT = 500.milliseconds
    val GRACE = 200.milliseconds

    // Room for the encoder ladder on a cold runtime, and short of the stand-in's sleep.
    val TEST_TIMEOUT = 2.minutes
  }
}
