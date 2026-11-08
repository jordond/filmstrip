package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.process.PACKET_DROP_FILTER
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * Runs the toolchain lookup against a script that prints an ffmpeg version banner and nothing else, so the floor is
 * checked against what a real `ffmpeg -version` call hands back.
 */
class ToolchainFloorTest {
  @Test
  fun `an ffmpeg below 8_0 is refused naming its version and the floor`() =
    runTest {
      val result = locate("7.1.2-0+deb13u1")

      val error = result.shouldBeInstanceOf<ToolchainResult.Unavailable>().error
      error.tool shouldBe "ffmpeg"
      error.foundVersion shouldBe "7.1.2-0+deb13u1"
      error.message shouldContain "7.1.2-0+deb13u1 is too old"
      error.message shouldContain "ffmpeg 8.0 or newer"
    }

  @Test
  fun `an ffmpeg at the floor is used`() =
    runTest {
      val toolchain = locate("8.0.1-3ubuntu2").shouldBeInstanceOf<ToolchainResult.Available>().toolchain

      toolchain.version.printed shouldBe "8.0.1-3ubuntu2"
    }

  @Test
  fun `a git build is held to the floor by its libavformat major`() =
    runTest {
      val old = locate("N-121284-g4c9e0eb0e6", libavformat = 61)
      old.shouldBeInstanceOf<ToolchainResult.Unavailable>().error.message shouldContain
        "N-121284-g4c9e0eb0e6 is too old"
      locate("N-121284-g4c9e0eb0e6", libavformat = 62).shouldBeInstanceOf<ToolchainResult.Available>()
    }

  @Test
  fun `a git build that prints no libavformat version is refused for it`() =
    runTest {
      val error = locate("2025-09-01-git-1d33e7c3a5").shouldBeInstanceOf<ToolchainResult.Unavailable>().error

      error.foundVersion shouldBe "2025-09-01-git-1d33e7c3a5"
      error.message shouldContain "its libavformat version could not be read"
      error.message shouldContain "ffmpeg 8.0 or newer"
    }

  @Test
  fun `an ffmpeg without the bitstream filter a copy ends on is refused naming it`() =
    runTest {
      val error =
        locate("8.0.1-3ubuntu2", bitstreamFilters = listOf("h264_mp4toannexb"))
          .shouldBeInstanceOf<ToolchainResult.Unavailable>()
          .error

      error.tool shouldBe "ffmpeg"
      error.foundVersion shouldBe "8.0.1-3ubuntu2"
      error.message shouldContain "no `$PACKET_DROP_FILTER` bitstream filter"
      error.message shouldContain "ffmpeg 8.0 or newer"
    }

  // Looks up a stand-in ffmpeg whose banner names [version], printing a libavformat line at [libavformat] when given,
  // and listing [bitstreamFilters] when asked for them.
  private suspend fun locate(
    version: String,
    libavformat: Int? = null,
    bitstreamFilters: List<String> = listOf(PACKET_DROP_FILTER),
  ): ToolchainResult {
    val directory = Files.createTempDirectory("filmstrip-floor").toFile()
    try {
      val library = libavformat?.let { "\nlibavformat    $it.  3.100 / $it.  3.100" }.orEmpty()
      val banner = "ffmpeg version $version Copyright (c) 2000-2025 the FFmpeg developers$library"
      val listing = (listOf("Bitstream filters:") + bitstreamFilters).joinToString("\n")
      val ffmpeg = script(directory, "ffmpeg", banner, listing)
      script(directory, "ffprobe", "ffprobe version $version")
      return ToolchainLocator(FfmpegConfig(executablePath = ffmpeg.absolutePath), ProcessRunner()).toolchain()
    } finally {
      directory.deleteRecursively()
    }
  }

  private fun script(
    directory: File,
    name: String,
    banner: String,
    bitstreamFilters: String = "",
  ): File =
    File(directory, name).apply {
      writeText(
        "#!/bin/sh\ncase \"$*\" in *-bsfs*) printf '%s\\n' '$bitstreamFilters' ;; " +
          "*) printf '%s\\n' '$banner' ;; esac\n",
      )
      setExecutable(true)
    }
}
