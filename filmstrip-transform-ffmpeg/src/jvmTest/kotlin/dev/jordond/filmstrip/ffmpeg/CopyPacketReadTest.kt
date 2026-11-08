package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.probe.packetCapReason
import dev.jordond.filmstrip.ffmpeg.internal.process.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketRead
import dev.jordond.filmstrip.transform.internal.copy.copyPacketCap
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reads a copy's packets through a stand-in ffprobe, so what the runtime does with a listing is checked without a
 * source to list.
 */
class CopyPacketReadTest {
  private val directory = Files.createTempDirectory("filmstrip-packets").toFile()
  private val runtime = FfmpegRuntime.unshared(FfmpegConfig())

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  // A listing that never ends stands in for a source carrying far more packets than its window could, and the read
  // stops at the cap rather than holding all of them.
  @Test
  fun `a read past the cap is stopped and fails naming it`() =
    runTest {
      val toolchain = toolchain("exec yes '0.0,K__'")
      val from = 2.seconds
      val to = 2_250.milliseconds

      val read = runtime.copyPackets(toolchain, source().path, from, to)

      read.shouldBeInstanceOf<CopyPacketRead.Failed>().reason shouldBe
        packetCapReason(copyPacketCap(from, to), from, to)
    }

  @Test
  fun `a read at the cap is kept whole`() =
    runTest {
      val from = 2.seconds
      val to = 2_250.milliseconds
      val cap = copyPacketCap(from, to)
      val toolchain = toolchain("yes '0.0,K__' | head -n $cap")

      val read = runtime.copyPackets(toolchain, source().path, from, to)

      read.shouldBeInstanceOf<CopyPacketRead.Read>().packets.size shouldBe cap
    }

  private fun source(): File = File(directory, "source.mp4").apply { writeText("source") }

  // A toolchain whose ffprobe runs [body] whatever it is asked.
  private fun toolchain(body: String): Toolchain {
    val ffprobe =
      File(directory, "ffprobe").apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
      }
    return Toolchain(
      ffmpeg = "/nonexistent/ffmpeg",
      ffprobe = ffprobe.path,
      version = FfmpegVersion("ffmpeg version 9.0.1", 9, 0),
      filters = emptySet(),
      encoders = emptySet(),
    )
  }
}
