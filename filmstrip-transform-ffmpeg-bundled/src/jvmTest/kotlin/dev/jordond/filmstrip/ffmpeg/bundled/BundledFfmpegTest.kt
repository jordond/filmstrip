package dev.jordond.filmstrip.ffmpeg.bundled

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.ffmpeg.ffmpeg
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacpp.Loader
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Which ffmpeg an instance runs with this artifact on the classpath, read off the toolchain it announces.
 */
class BundledFfmpegTest {
  private val scratch = Files.createTempDirectory("filmstrip-bundled").toFile()

  @AfterTest
  fun delete() {
    scratch.deleteRecursively()
  }

  @Test
  fun `a discovered artifact runs the ffmpeg it carries out of the javacpp cache`() =
    runTest {
      val toolchain = toolchainOf { }

      toolchain.getValue(DiagnosticEvent.Detail.VERSION) shouldStartWith "8.1"
      val ffmpeg = File(toolchain.getValue(DiagnosticEvent.Detail.FFMPEG))
      val ffprobe = File(toolchain.getValue(DiagnosticEvent.Detail.FFPROBE))
      ffmpeg.path shouldStartWith Loader.getCacheDir().path
      ffmpeg.canExecute() shouldBe true
      ffprobe.canExecute() shouldBe true
      ffprobe.parentFile shouldBe ffmpeg.parentFile
    }

  @Test
  fun `a backend the block registered itself still runs the carried ffmpeg`() =
    runTest {
      val toolchain = toolchainOf { ffmpegBackend() }

      toolchain.getValue(DiagnosticEvent.Detail.FFMPEG) shouldStartWith Loader.getCacheDir().path
    }

  @Test
  fun `a configured path is used ahead of the carried ffmpeg`() =
    runTest {
      val configured = stub("8.0.1")

      val toolchain = toolchainOf { ffmpeg { executablePath = configured.path } }

      toolchain.getValue(DiagnosticEvent.Detail.FFMPEG) shouldBe configured.path
      toolchain.getValue(DiagnosticEvent.Detail.VERSION) shouldBe "8.0.1"
    }

  @Test
  fun `with discovery off the builder call runs the carried ffmpeg`() =
    runTest {
      val toolchain =
        toolchainOf {
          autoRegister(false)
          ffmpegBundled()
        }

      toolchain.getValue(DiagnosticEvent.Detail.FFMPEG) shouldStartWith Loader.getCacheDir().path
    }

  // Builds an instance, asks it something that needs ffmpeg, and returns the toolchain it announced.
  private suspend fun toolchainOf(block: FilmstripBuilder.() -> Unit): Map<String, String> {
    val events = mutableListOf<DiagnosticEvent>()
    val filmstrip =
      Filmstrip {
        addDiagnosticListener { events += it }
        block()
      }

    filmstrip.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>()

    return events.single { it.name == DiagnosticEvent.TOOLCHAIN }.detail
  }

  // A stand-in ffmpeg at [version] that lists the one bitstream filter the backend asks for and no encoders.
  private fun stub(version: String): File {
    File(scratch, "ffprobe").apply {
      writeText("#!/bin/sh\nexit 1\n")
      setExecutable(true)
    }
    return File(scratch, "ffmpeg").apply {
      writeText(
        "#!/bin/sh\ncase \"$*\" in *-bsfs*) printf '%s\\n' 'Bitstream filters:' 'noise' ;; " +
          "*-version*) printf '%s\\n' 'ffmpeg version $version Copyright (c) 2000-2025 the FFmpeg developers' ;; " +
          "esac\n",
      )
      setExecutable(true)
    }
  }
}
