package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.process.PACKET_DROP_FILTER
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Looks up a stand-in ffmpeg that hangs on one of the calls the lookup makes, under a short deadline, so a binary that
 * never answers is refused naming the call rather than hanging the lookup or read as a build that carries nothing.
 */
class ToolchainDeadlineTest {
  private val directory = Files.createTempDirectory("filmstrip-toolchain-deadline").toFile()

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  @Test
  fun `an ffmpeg that never answers -version is refused naming it`() =
    runTest(timeout = TEST_TIMEOUT) {
      val error = locate(hangsOn = "-version").shouldBeInstanceOf<ToolchainResult.Unavailable>().error

      error.tool shouldBe "ffmpeg"
      error.foundVersion.shouldBeNull()
      error.message shouldContain "`ffmpeg -version` was still running after $DEADLINE"
    }

  @Test
  fun `an ffmpeg that never lists its encoders is refused naming the listing`() =
    runTest(timeout = TEST_TIMEOUT) {
      val error = locate(hangsOn = "-encoders").shouldBeInstanceOf<ToolchainResult.Unavailable>().error

      error.foundVersion shouldBe "8.0.1"
      error.message shouldContain "`ffmpeg -encoders` was still running after $DEADLINE"
    }

  // Answers every call at once except the one naming [hangsOn], which it sleeps through.
  private suspend fun locate(hangsOn: String): ToolchainResult {
    val ffmpeg =
      File(directory, "ffmpeg").apply {
        writeText(
          "#!/bin/sh\ncase \"$*\" in *$hangsOn*) exec sleep 30 ;; " +
            "*-bsfs*) printf '%s\\n' 'Bitstream filters:' '$PACKET_DROP_FILTER' ;; " +
            "*) printf '%s\\n' 'ffmpeg version 8.0.1 Copyright (c) 2000-2025 the FFmpeg developers' ;; esac\n",
        )
        setExecutable(true)
      }
    File(directory, "ffprobe").apply {
      writeText("#!/bin/sh\n")
      setExecutable(true)
    }
    val config = FfmpegConfig(executablePath = ffmpeg.absolutePath)
    return ToolchainLocator(config, ProcessRunner(GRACE), DEADLINE).toolchain()
  }

  private companion object {
    val DEADLINE = 300.milliseconds
    val GRACE = 200.milliseconds

    // Past the deadline and the stop's grace, and short of the stand-in's sleep.
    val TEST_TIMEOUT = 20.seconds
  }
}
