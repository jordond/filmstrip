package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegProber
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.probe.SYNC_SAMPLE_WINDOW
import dev.jordond.filmstrip.ffmpeg.internal.process.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.process.PACKET_DROP_FILTER
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeAnswer
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeOperation
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.process.probeExpiredReason
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketRead
import dev.jordond.filmstrip.transform.internal.copy.copyPacketSpan
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reads a source through a stand-in ffprobe that never finishes, under a deadline far shorter than the real one, so
 * every short read is shown to stop its child and refuse naming the deadline rather than hang.
 */
class ProbeDeadlineReadTest {
  private val directory = Files.createTempDirectory("filmstrip-deadline").toFile()
  private val pidFile = File(directory, "ffprobe.pid")
  private val windows = mutableListOf<Duration>()
  private val runtime =
    FfmpegRuntime.unshared(
      FfmpegConfig(executablePath = ffmpeg().path, probePath = hangingProbe().path),
      deadline = { window -> DEADLINE.also { windows += window } },
      runner = ProcessRunner(GRACE),
    )
  private val toolchain = toolchain()
  private val source = File(directory, "source.mp4").apply { writeText("source") }
  private val expired = probeExpiredReason("ffprobe", DEADLINE)

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  @Test
  fun `a source read past its deadline is refused as timed out`() =
    runTest(timeout = TEST_TIMEOUT) {
      val result = FfmpegProber(runtime).probe(MediaSource.of(source.path))

      result.shouldBeInstanceOf<ProbeResult.Failure>().error shouldBe
        FilmstripError.TimedOut(ProbeOperation.SOURCE_PROBE, DEADLINE, expired)
      childIsGone()
    }

  @Test
  fun `a source read past its deadline is asked again`() =
    runTest(timeout = TEST_TIMEOUT) {
      runtime.probe(toolchain, source.path).shouldBeInstanceOf<ProbeAnswer.Expired>()
      runtime.probe(toolchain, source.path).shouldBeInstanceOf<ProbeAnswer.Expired>()

      runtime.probeSpawns shouldBe 2
    }

  @Test
  fun `a colour read past its deadline is expired and asked again`() =
    runTest(timeout = TEST_TIMEOUT) {
      runtime.colorRead(toolchain, source.path).shouldBeInstanceOf<ProbeAnswer.Expired>().timedOut() shouldBe
        FilmstripError.TimedOut(ProbeOperation.COLOR_READ, DEADLINE, expired)
      runtime.colorRead(toolchain, source.path).shouldBeInstanceOf<ProbeAnswer.Expired>()

      runtime.sdColorSpawns shouldBe 2
      childIsGone()
    }

  @Test
  fun `a sync sample read past its deadline is expired and asked again`() =
    runTest(timeout = TEST_TIMEOUT) {
      runtime
        .syncSampleAtOrBefore(
          toolchain,
          source.path,
          CUT,
        ).shouldBeInstanceOf<ProbeAnswer.Expired>()
        .timedOut() shouldBe
        FilmstripError.TimedOut(ProbeOperation.SYNC_SAMPLE_READ, DEADLINE, expired)
      runtime.syncSampleAtOrBefore(toolchain, source.path, CUT).shouldBeInstanceOf<ProbeAnswer.Expired>()

      runtime.syncSampleSpawns shouldBe 2
      windows.takeLast(2) shouldContainExactly listOf(SYNC_SAMPLE_WINDOW, SYNC_SAMPLE_WINDOW)
      childIsGone()
    }

  @Test
  fun `a packet read past its deadline is expired naming it`() =
    runTest(timeout = TEST_TIMEOUT) {
      val from = 2.seconds
      val to = 2_250.milliseconds

      val read = runtime.copyPackets(toolchain, source.path, from, to)

      val expiredRead = read.shouldBeInstanceOf<CopyPacketRead.Expired>()
      expiredRead.operation shouldBe ProbeOperation.COPY_PACKET_READ
      expiredRead.deadline shouldBe DEADLINE
      expiredRead.reason shouldBe expired
      windows.last() shouldBe copyPacketSpan(from, to)
      childIsGone()
    }

  // The stand-in ffprobe writes its process id and then becomes the sleep, so the id is the child the runner spawned.
  private fun childIsGone() {
    val pid = pidFile.readText().trim().toLong()
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false) shouldBe false
  }

  private fun hangingProbe(): File = script("ffprobe", "echo $$ > '${pidFile.path}'\nexec sleep 30")

  // Answers the toolchain lookup at once, so only the source read runs into the deadline.
  private fun ffmpeg(): File =
    script(
      "ffmpeg",
      "case \"$*\" in *-bsfs*) printf '%s\\n' 'Bitstream filters:' '$PACKET_DROP_FILTER' ;; " +
        "*) printf '%s\\n' 'ffmpeg version 8.0.1 Copyright (c) 2000-2025 the FFmpeg developers' ;; esac",
    )

  private fun script(
    name: String,
    body: String,
  ): File =
    File(directory, name).apply {
      writeText("#!/bin/sh\n$body\n")
      setExecutable(true)
    }

  private fun toolchain(): Toolchain =
    Toolchain(
      ffmpeg = File(directory, "ffmpeg").path,
      ffprobe = File(directory, "ffprobe").path,
      version = FfmpegVersion("ffmpeg version 9.0.1", 9, 0),
      filters = emptySet(),
      encoders = emptySet(),
    )

  private companion object {
    val DEADLINE = 300.milliseconds
    val GRACE = 200.milliseconds
    val CUT = 5.seconds

    // Past the deadline and the stop's grace, and well short of the stand-in's sleep, so a read that never stops
    // fails here rather than hanging the suite.
    val TEST_TIMEOUT = 20.seconds
  }
}
