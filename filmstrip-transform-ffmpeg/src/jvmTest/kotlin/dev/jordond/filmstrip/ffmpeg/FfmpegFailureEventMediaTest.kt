package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent.Detail
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.ffmpeg.internal.failure.FFMPEG_BACKEND
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * A real ffmpeg failure, exported through `Filmstrip`, reaches a diagnostic listener as exactly one failure event that
 * names ffmpeg and carries the code the process exited with.
 */
class FfmpegFailureEventMediaTest {
  private val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  private val landscape = File(fixtures, "export_landscape.mp4")

  private val events = CopyOnWriteArrayList<DiagnosticEvent>()
  private val filmstrip =
    Filmstrip {
      ffmpegBackend()
      addDiagnosticListener { events += it }
    }

  // The parent directory exists, so the check before the spawn passes, and ffmpeg is the one refused the output.
  @Test
  fun `an output ffmpeg may not write fails the export once with its exit code`() =
    runTest(timeout = 2.minutes) {
      if (!landscape.isFile) fail("The fixture ${landscape.path} was not downloaded.")
      val locked = Files.createTempDirectory("filmstrip-locked").toFile()
      withClue("the directory could not be made read only") { locked.setWritable(false, false) shouldBe true }
      try {
        val composition = compositionOf { clip(MediaSource.of(landscape.absolutePath)) }
        val sink = MediaSink.of(File(locked, "out.mp4").absolutePath)

        filmstrip
          .export(composition, ExportSpec(heightPx = 240), sink)
          .toList()
          .last()
          .shouldBeInstanceOf<ExportStatus.Failure>()

        val failure = events.filter { it.name == DiagnosticEvent.FAILURE }.shouldHaveSize(1).single()
        withClue(failure.detail) { failure.source shouldBe FFMPEG_BACKEND }
        failure.detail[Detail.STAGE] shouldBe "export"
        failure.detail[Detail.ERROR_CODE]?.toIntOrNull() shouldNotBe null
        failure.detail[Detail.ERROR_CODE] shouldNotBe "0"
      } finally {
        locked.setWritable(true, false)
        locked.deleteRecursively()
      }
    }
}
