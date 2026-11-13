package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Where the toolchain lookup takes its binaries from when a source of them is set, against stand-in binaries that
 * each print their own version.
 */
class ToolchainSourceTest {
  private val root = Files.createTempDirectory("filmstrip-source").toFile()
  private val configured = toolchainStub(root, "configured", "8.0.1")
  private val supplied = toolchainStub(root, "supplied", "8.1.2")
  private val onPath = toolchainStub(root, "path", "8.2.0")
  private var asked = 0
  private var searched = 0
  private val source = FfmpegBinaries { found(supplied).also { asked++ } }

  @AfterTest
  fun delete() {
    root.deleteRecursively()
  }

  @Test
  fun `a configured path is used without asking the source`() =
    runTest {
      val toolchain = locate(FfmpegConfig(executablePath = "$configured/ffmpeg")).available()

      toolchain.version.printed shouldBe "8.0.1"
      asked shouldBe 0
    }

  @Test
  fun `the environment variable is used without asking the source`() =
    runTest {
      val toolchain = locate(environment = mapOf("FILMSTRIP_FFMPEG" to "$configured/ffmpeg")).available()

      toolchain.version.printed shouldBe "8.0.1"
      asked shouldBe 0
    }

  @Test
  fun `the source is used ahead of the path for both binaries`() =
    runTest {
      val toolchain = locate().available()

      toolchain.ffmpeg shouldBe "$supplied/ffmpeg"
      toolchain.ffprobe shouldBe "$supplied/ffprobe"
      toolchain.version.printed shouldBe "8.1.2"
      searched shouldBe 0
    }

  @Test
  fun `a configured ffprobe is used beside the source's ffmpeg`() =
    runTest {
      val toolchain = locate(FfmpegConfig(probePath = "$configured/ffprobe")).available()

      toolchain.ffmpeg shouldBe "$supplied/ffmpeg"
      toolchain.ffprobe shouldBe "$configured/ffprobe"
    }

  @Test
  fun `the path is searched when there is no source`() =
    runTest {
      val toolchain = locate(binaries = null).available()

      toolchain.ffmpeg shouldBe "$onPath/ffmpeg"
      toolchain.ffprobe shouldBe "$onPath/ffprobe"
    }

  @Test
  fun `a source with nothing for this machine refuses with its message and never searches the path`() =
    runTest {
      val result = locate(binaries = { FfmpegBinariesResult.Unavailable("no ffmpeg for plan9-mips") })

      val error = result.shouldBeInstanceOf<ToolchainResult.Unavailable>().error
      error.tool shouldBe "ffmpeg"
      error.foundVersion shouldBe null
      error.message shouldBe "no ffmpeg for plan9-mips"
      searched shouldBe 0
    }

  @Test
  fun `a source naming a file that is not there refuses and never searches the path`() =
    runTest {
      val gone = FfmpegBinariesResult.Found("$root/gone/ffmpeg", "$root/gone/ffprobe")

      locate(binaries = { gone }).shouldBeInstanceOf<ToolchainResult.Unavailable>()
      searched shouldBe 0
    }

  @Test
  fun `a configured ffprobe that is not there gives way to the one beside ffmpeg`() =
    runTest {
      val config = FfmpegConfig(executablePath = "$configured/ffmpeg", probePath = "$root/gone/ffprobe")

      val toolchain = locate(config, binaries = null).available()

      toolchain.ffprobe shouldBe "$configured/ffprobe"
      searched shouldBe 0
    }

  @Test
  fun `an ffmpeg that exits without a version is refused with what it printed`() =
    runTest {
      val broken = File(root, "broken").apply { mkdirs() }
      File(broken, "ffmpeg").apply {
        writeText("#!/bin/sh\necho 'version GLIBC_2.35 not found' >&2\nexit 1\n")
        setExecutable(true)
      }
      File(broken, "ffprobe").writeText("")

      val error = locate(binaries = { found(broken) }).shouldBeInstanceOf<ToolchainResult.Unavailable>().error

      error.message shouldContain "$broken/ffmpeg does not run on this machine"
      error.message shouldContain "exited with 1"
      error.message shouldContain "GLIBC_2.35 not found"
    }

  @Test
  fun `an ffmpeg from the source is held to the floor`() =
    runTest {
      val old = toolchainStub(root, "old", "7.1.2")

      val error = locate(binaries = { found(old) }).shouldBeInstanceOf<ToolchainResult.Unavailable>().error

      error.foundVersion shouldBe "7.1.2"
      error.message shouldContain "7.1.2 is too old"
    }

  @Test
  fun `a missing ffmpeg names the bundled artifact as a way out`() =
    runTest {
      val error = locate(binaries = null, path = emptyList()).shouldBeInstanceOf<ToolchainResult.Unavailable>().error

      error.message shouldContain "FfmpegConfig.executablePath"
      error.message shouldContain "FILMSTRIP_FFMPEG"
      error.message shouldContain "filmstrip-transform-ffmpeg-bundled"
    }

  @Test
  fun `a runtime is shared per config and source`() {
    val config = FfmpegConfig(threads = 3)
    val other = FfmpegBinaries { found(supplied) }

    FfmpegRuntime.of(config, source) shouldBeSameInstanceAs FfmpegRuntime.of(config, source)
    FfmpegRuntime.of(config, source) shouldNotBeSameInstanceAs FfmpegRuntime.of(config, other)
    FfmpegRuntime.of(config, source) shouldNotBeSameInstanceAs FfmpegRuntime.of(config)
  }

  @Test
  fun `the backend runs the source the builder was given`() {
    val components =
      Filmstrip {
        ffmpegBackend()
        ffmpegBinaries(source)
      }.components

    val engine = components.exportEngineFactories.single().create(components)

    engine.shouldBeInstanceOf<FfmpegExportEngine>().runtime.binaries shouldBeSameInstanceAs source
  }

  private suspend fun locate(
    config: FfmpegConfig = FfmpegConfig(),
    binaries: FfmpegBinaries? = source,
    environment: Map<String, String> = emptyMap(),
    path: List<String> = listOf(onPath.absolutePath),
  ): ToolchainResult =
    ToolchainLocator(
      config = config,
      runner = ProcessRunner(),
      binaries = binaries,
      environment = { environment[it] },
      searchEntries = { path.also { searched++ } },
    ).toolchain()

  private fun ToolchainResult.available(): Toolchain = shouldBeInstanceOf<ToolchainResult.Available>().toolchain

  private fun found(directory: File): FfmpegBinariesResult =
    FfmpegBinariesResult.Found("$directory/ffmpeg", "$directory/ffprobe")
}
