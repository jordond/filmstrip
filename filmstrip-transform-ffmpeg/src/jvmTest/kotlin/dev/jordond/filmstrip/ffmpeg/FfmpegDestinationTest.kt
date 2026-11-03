package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.internal.process.NOT_A_FILE_URL
import dev.jordond.filmstrip.ffmpeg.internal.process.SOURCE_GONE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.SINK_PARENT_MISSING
import dev.jordond.filmstrip.media.describe
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * What the ffmpeg engine refuses before it spawns anything, and how a refusal reaches the caller as a status.
 *
 * Each export runs on a runtime of its own, so the count of spawned exports is this test's alone. Skipped rather than
 * failed when there is no ffmpeg, on the same terms as the export test.
 */
class FfmpegDestinationTest {
  private val fixture = FfmpegExportFixture()
  private val root: File = createTempDirectory("filmstrip-ffmpeg-destination").toFile()
  private val runtime = FfmpegRuntime.unshared(FfmpegConfig())
  private val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `refuses a destination whose directory is missing without spawning ffmpeg`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val path = File(root, "missing/out.mp4").path
      val engine = FfmpegExportEngine(components, runtime)

      val statuses = engine.export(planned(engine, clipOf(fixture.landscape)), MediaSink.of(path)).toList()

      val refusal = failureOf(statuses).shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
      refusal.sink shouldBe path
      refusal.message shouldBe SINK_PARENT_MISSING
      runtime.streamSpawns shouldBe 0
    }

  @Test
  fun `refuses a sink uri that names no file without spawning ffmpeg`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val engine = FfmpegExportEngine(components, runtime)

      val statuses =
        engine
          .export(
            planned(engine, clipOf(fixture.landscape)),
            MediaSink.ofUri("https://example.com/out.mp4"),
          ).toList()

      val refusal = failureOf(statuses).shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
      refusal.sink shouldBe "https://example.com/out.mp4"
      refusal.message shouldBe NOT_A_FILE_URL
      runtime.streamSpawns shouldBe 0
    }

  @Test
  fun `names a clip that went missing after planning`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val clip = fixture.landscape.copyTo(File(root, "clip.mp4"))
      val engine = FfmpegExportEngine(components, runtime)
      val plan = planned(engine, clipOf(clip))
      clip.delete()

      val statuses = engine.export(plan, MediaSink.of(File(root, "out.mp4").path)).toList()

      val refusal = failureOf(statuses).shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
      refusal.source shouldBe MediaSource.of(clip.absolutePath).describe()
      refusal.message shouldBe SOURCE_GONE
      runtime.streamSpawns shouldBe 0
    }

  @Test
  fun `names an overlay picture that went missing after planning`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val picture = File(root, "logo.png").apply { writeBytes(fixture.watermarkPng(OVERLAY_SIZE)) }
      val image = ImageSource.of(picture.absolutePath)
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture.landscape.absolutePath))
          effects { add(ImageOverlay(image, Corner.TopStart)) }
        }
      val engine = FfmpegExportEngine(components, runtime)
      val plan = planned(engine, composition)
      picture.delete()

      val statuses = engine.export(plan, MediaSink.of(File(root, "out.mp4").path)).toList()

      failureOf(statuses).shouldBeInstanceOf<FilmstripError.SourceUnreadable>().source shouldBe image.describe()
      runtime.streamSpawns shouldBe 0
    }

  // A regular file where the scratch directory should go is what a scratch directory that cannot be made looks like.
  @Test
  fun `reports a scratch directory it cannot make as a failure rather than throwing`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val blocked = File(root, "blocked").apply { writeText("not a directory") }
      val engine = FfmpegExportEngine(components, runtime, scratchRoot = Path(blocked.path))

      val statuses = engine.export(planned(engine, clipOf(fixture.landscape)), MediaSink.of(File(root, "out.mp4").path))

      val failure = failureOf(statuses.toList()).shouldBeInstanceOf<FilmstripError.Underlying>()
      failure.platformCode shouldBe FilmstripError.Underlying.NO_PLATFORM_CODE
      runtime.streamSpawns shouldBe 0
    }

  @Test
  fun `refuses a preview whose scratch directory it cannot make rather than throwing`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val blocked = File(root, "blocked").apply { writeText("not a directory") }
      val engine = FfmpegExportEngine(components, runtime, scratchRoot = Path(blocked.path))

      val result = engine.openPreview(clipOf(fixture.landscape), ExportSpec())

      val failure =
        result
          .shouldBeInstanceOf<PreviewStreamResult.Refused>()
          .error
          .shouldBeInstanceOf<FilmstripError.Underlying>()
      failure.platformCode shouldBe FilmstripError.Underlying.NO_PLATFORM_CODE
    }

  private fun clipOf(file: File): EditComposition = compositionOf { clip(MediaSource.of(file.absolutePath)) }

  private suspend fun planned(
    engine: FfmpegExportEngine,
    composition: EditComposition,
  ): ExportPlan =
    when (val verdict = engine.plan(composition, ExportSpec())) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
    }

  private fun failureOf(statuses: List<ExportStatus>): FilmstripError {
    withClue("the export started: $statuses") { statuses.none { it is ExportStatus.Started }.shouldBeTrue() }
    return statuses.single().shouldBeInstanceOf<ExportStatus.Failure>().error
  }
}
