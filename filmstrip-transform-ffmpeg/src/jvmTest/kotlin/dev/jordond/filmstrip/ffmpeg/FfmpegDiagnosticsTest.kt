package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The two facts a desktop bug report is asked for that no return value carries.
 *
 * Skipped rather than failed when there is no ffmpeg, on the same terms as the export test.
 */
class FfmpegDiagnosticsTest {
  private val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  private val landscape = File(fixtures, "export_landscape.mp4")

  private val events = mutableListOf<DiagnosticEvent>()
  private val filmstrip =
    Filmstrip {
      ffmpegBackend()
      addDiagnosticListener(DiagnosticListener { events += it })
    }

  @Test
  fun `names itself so a report can say which backend ran`() {
    val backend = assertNotNull(filmstrip.components.backends.firstOrNull { it.name == "ffmpeg" })
    assertEquals("dev.jordond.filmstrip:filmstrip-transform-ffmpeg", backend.artifact)
  }

  @Test
  fun `announces the version banner once, the first time anything asks`() =
    runTest(timeout = TIMEOUT) {
      if (!landscape.isFile) return@runTest

      filmstrip.capabilities()
      filmstrip.capabilities()

      val toolchain = events.filter { it.name == "toolchain" }
      assertEquals(1, toolchain.size, "the banner is announced once per engine")
      assertTrue(
        toolchain
          .single()
          .detail
          .getValue("banner")
          .startsWith("ffmpeg version"),
      )
      assertTrue(
        toolchain
          .single()
          .detail
          .getValue("ffmpeg")
          .isNotEmpty(),
      )
    }

  @Test
  fun `announces the command line it spawned`() =
    runTest(timeout = TIMEOUT) {
      if (!landscape.isFile) return@runTest

      val output = File.createTempFile("filmstrip-diagnostics", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(landscape.absolutePath)) }

      filmstrip
        .export(composition, ExportSpec(targetHeight = 240), MediaSink.of(output.absolutePath))
        .toList()

      val command = assertNotNull(events.firstOrNull { it.name == "invocation" }).detail.getValue("command")
      assertTrue(command.contains("-i "), "the command names its input")
      assertTrue(command.contains(output.absolutePath), "the command names its output")

      output.delete()
    }

  private companion object {
    val TIMEOUT = 2.minutes
  }
}
