package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent.Detail
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The two facts a desktop bug report is asked for that no return value carries.
 *
 * Skipped rather than failed when there is no ffmpeg, on the same terms as the export test.
 */
class FfmpegDiagnosticsMediaTest {
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
    val backend =
      filmstrip.components.backends
        .firstOrNull { it.name == "ffmpeg" }
        .shouldNotBeNull()
    backend.artifact shouldBe "dev.jordond.filmstrip:filmstrip-transform-ffmpeg"
  }

  @Test
  fun `announces the version banner once, the first time anything asks`() =
    runTest(timeout = TIMEOUT) {
      if (!landscape.isFile) return@runTest

      filmstrip.capabilities()
      filmstrip.capabilities()

      val toolchain = events.filter { it.name == DiagnosticEvent.TOOLCHAIN }
      withClue("the banner is announced once per engine") { toolchain.size shouldBe 1 }
      toolchain
        .single()
        .detail
        .getValue(Detail.BANNER)
        .startsWith("ffmpeg version")
        .shouldBeTrue()
      toolchain
        .single()
        .detail
        .getValue(Detail.FFMPEG)
        .isNotEmpty()
        .shouldBeTrue()
    }

  @Test
  fun `announces the command line it spawned`() =
    runTest(timeout = TIMEOUT) {
      if (!landscape.isFile) return@runTest

      val output = File.createTempFile("filmstrip-diagnostics", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(landscape.absolutePath)) }

      filmstrip
        .export(composition, ExportSpec(heightPx = 240), MediaSink.of(output.absolutePath))
        .toList()

      val command =
        events
          .firstOrNull {
            it.name == DiagnosticEvent.INVOCATION
          }.shouldNotBeNull()
          .detail
          .getValue(Detail.COMMAND)
      withClue("the command names its input") { command.contains("-i ").shouldBeTrue() }
      withClue("the command names its output") { command.contains(output.absolutePath).shouldBeTrue() }

      output.delete()
    }

  // A host that installs ffmpeg after the first attempt recovers on the next call, and the toolchain
  // that turned up is announced the same way a found one is.
  @Test
  fun `looks for a missing toolchain again and announces it once it turns up`() =
    runTest(timeout = TIMEOUT) {
      val found = ToolchainLocator(FfmpegConfig(), ProcessRunner()).toolchain()
      if (found !is ToolchainResult.Available) return@runTest

      val directory = Files.createTempDirectory("filmstrip-toolchain")
      val ffmpeg = directory.resolve("ffmpeg")
      val late = mutableListOf<DiagnosticEvent>()
      val installedLater =
        Filmstrip {
          ffmpegBackend(FfmpegConfig(executablePath = ffmpeg.toString()))
          addDiagnosticListener(DiagnosticListener { late += it })
        }

      installedLater.capabilities().shouldBeInstanceOf<CapabilitiesResult.Failure>()
      Files.createSymbolicLink(ffmpeg, Path.of(found.toolchain.ffmpeg))
      Files.createSymbolicLink(directory.resolve("ffprobe"), Path.of(found.toolchain.ffprobe))
      installedLater.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>()

      val announced = late.filter { it.name == DiagnosticEvent.TOOLCHAIN }
      announced.map { it.detail.keys.first() } shouldBe listOf(Detail.FOUND, Detail.FFMPEG)

      directory.toFile().deleteRecursively()
    }

  private companion object {
    val TIMEOUT = 2.minutes
  }
}
