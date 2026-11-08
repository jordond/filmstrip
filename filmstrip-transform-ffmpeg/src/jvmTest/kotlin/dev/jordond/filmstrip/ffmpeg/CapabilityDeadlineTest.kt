package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.process.PACKET_DROP_FILTER
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeOperation
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.transform.internal.RESOLUTION_LADDER
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
 * Measures the encoder ladder through a stand-in ffmpeg whose first encode never finishes, under a short deadline, so
 * a rung stopped at its deadline is shown to fail the capabilities read naming it and to be measured again next time.
 */
class CapabilityDeadlineTest {
  private val directory = Files.createTempDirectory("filmstrip-capability-deadline").toFile()
  private val pidFile = File(directory, "ffmpeg.pid")
  private val hungOnce = File(directory, "hung")
  private val runtime =
    FfmpegRuntime.unshared(
      FfmpegConfig(executablePath = ffmpeg().path, probePath = script("ffprobe", "exit 1").path),
      deadline = { DEADLINE },
      runner = ProcessRunner(GRACE),
    )
  private val engine = FfmpegExportEngine(ComponentRegistry.Builder().build(), runtime)

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  @Test
  fun `a rung past its deadline fails capabilities naming it`() =
    runTest(timeout = TEST_TIMEOUT) {
      val error = engine.capabilities().shouldBeInstanceOf<CapabilitiesResult.Failure>().error

      val timedOut = error.shouldBeInstanceOf<FilmstripError.TimedOut>()
      timedOut.operation shouldBe ProbeOperation.ENCODER_TEST
      timedOut.deadline shouldBe DEADLINE
      timedOut.message shouldContain "`libx264`"
      timedOut.message shouldContain "$DEADLINE"
      childIsGone()
    }

  @Test
  fun `a rung past its deadline is measured again and the answer after it is held`() =
    runTest(timeout = TEST_TIMEOUT) {
      engine.capabilities().shouldBeInstanceOf<CapabilitiesResult.Failure>()

      val capabilities = engine.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities
      engine.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>()

      capabilities.video.single().maxSize shouldBe RESOLUTION_LADDER.first()
      runtime.capabilityMeasures shouldBe 2
    }

  // Answers the toolchain lookup at once and lists only libx264. The first encode writes its process id and then
  // becomes the sleep, and every one after it succeeds.
  private fun ffmpeg(): File =
    script(
      "ffmpeg",
      "case \"$*\" in " +
        "*lavfi*) if [ ! -e '${hungOnce.path}' ]; then touch '${hungOnce.path}'; echo $$ > '${pidFile.path}'; " +
        "exec sleep 30; fi ;; " +
        "*-bsfs*) printf '%s\\n' 'Bitstream filters:' '$PACKET_DROP_FILTER' ;; " +
        "*-encoders*) printf '%s\\n' 'Encoders:' ' ------' ' V....D libx264 H.264' ;; " +
        "*-version*) printf '%s\\n' 'ffmpeg version 8.0.1 Copyright (c) 2000-2025 the FFmpeg developers' ;; esac",
    )

  private fun script(
    name: String,
    body: String,
  ): File =
    File(directory, name).apply {
      writeText("#!/bin/sh\n$body\n")
      setExecutable(true)
    }

  private fun childIsGone() {
    val pid = pidFile.readText().trim().toLong()
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false) shouldBe false
  }

  private companion object {
    val DEADLINE = 300.milliseconds
    val GRACE = 200.milliseconds

    // Past the deadline and the stop's grace, and short of the stand-in's sleep.
    val TEST_TIMEOUT = 20.seconds
  }
}
