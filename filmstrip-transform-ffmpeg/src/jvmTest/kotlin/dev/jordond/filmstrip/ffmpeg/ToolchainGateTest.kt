package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.support.suiteToolchainProblem
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Runs the suite's toolchain gate against scripts that print an ffmpeg version banner and nothing else, and against a
 * path with no binary at all.
 */
class ToolchainGateTest {
  private val directory = Files.createTempDirectory("filmstrip-gate").toFile()

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  @Test
  fun `an ffmpeg below the floor is refused naming its version the floor and the variable`() =
    runTest {
      val problem = suiteToolchainProblem(stub("7.1.2-0+deb13u1")).shouldNotBeNull()

      problem shouldContain "found `ffmpeg` 7.1.2-0+deb13u1"
      problem shouldContain "8.0 or newer"
      problem shouldContain "FILMSTRIP_FFMPEG"
    }

  @Test
  fun `no ffmpeg at all is refused naming the floor and the variable`() =
    runTest {
      val problem =
        suiteToolchainProblem(FfmpegConfig(executablePath = File(directory, "ffmpeg").absolutePath)).shouldNotBeNull()

      problem shouldContain "found `ffmpeg` nowhere"
      problem shouldContain "8.0 or newer"
      problem shouldContain "FILMSTRIP_FFMPEG"
    }

  @Test
  fun `an ffmpeg at the floor with no ten bit encoder is refused`() =
    runTest {
      val problem = suiteToolchainProblem(stub("8.0.1-3ubuntu2")).shouldNotBeNull()

      problem shouldContain "ffmpeg 8.0.1-3ubuntu2 has no encoder for it"
      problem shouldContain "FILMSTRIP_FFMPEG"
    }

  @Test
  fun `an hdr encoder that fails its probe is named`() =
    runTest {
      val config = stub("8.0.1-3ubuntu2", encoders = listOf("hevc_videotoolbox", "libx265"))

      val problem = suiteToolchainProblem(config).shouldNotBeNull()

      problem shouldContain "hevc_videotoolbox on ffmpeg 8.0.1-3ubuntu2 failed the HDR probe"
      problem shouldNotContain "libx265"
      problem shouldContain "FILMSTRIP_FFMPEG"
    }

  @Test
  fun `the host toolchain passes`() =
    runTest {
      suiteToolchainProblem().shouldBeNull()
    }

  /**
   * An ffmpeg that lists [encoders] and encodes with them, and fails any ten-bit encode.
   */
  private fun stub(
    version: String,
    encoders: List<String> = emptyList(),
  ): FfmpegConfig {
    val listing = encoders.joinToString("\n") { " V..... $it  stub" }
    script(
      "ffmpeg",
      "ffmpeg version $version Copyright (c) 2000-2025 the FFmpeg developers",
      "*-encoders*) echo '$listing' ;; *format=p010le*|*format=yuv420p10le*) exit 1 ;; ",
    )
    script("ffprobe", "ffprobe version $version")
    return FfmpegConfig(executablePath = File(directory, "ffmpeg").absolutePath)
  }

  private fun script(
    name: String,
    banner: String,
    cases: String = "",
  ) {
    File(directory, name).apply {
      writeText("#!/bin/sh\ncase \"$*\" in $cases *) echo '$banner' ;; esac\n")
      setExecutable(true)
    }
  }
}
