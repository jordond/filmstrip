package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.process.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.ffmpeg.support.answer
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketRead
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads a source through a stand-in ffprobe that prints the file it is pointed at, then rewrites that file at the same
 * path, so a cached answer that outlived the file it was read from shows as the old listing.
 */
class RewrittenSourceTest {
  private val directory = Files.createTempDirectory("filmstrip-rewritten").toFile()
  private val runtime = FfmpegRuntime.unshared(FfmpegConfig())
  private val toolchain = toolchain()
  private val source = File(directory, "source.mp4")

  @AfterTest
  fun clean() {
    directory.deleteRecursively()
  }

  @Test
  fun `a sync sample is read once for a file left alone`() =
    runTest {
      source.writeText("1.0\n")

      runtime.syncSampleAtOrBefore(toolchain, source.path, CUT).answer() shouldBe 1.seconds
      runtime.syncSampleAtOrBefore(toolchain, source.path, CUT).answer() shouldBe 1.seconds

      runtime.syncSampleSpawns shouldBe 1
    }

  @Test
  fun `a sync sample is read again once the file is written at the same size`() =
    runTest {
      source.writeText("1.0\n")
      runtime.syncSampleAtOrBefore(toolchain, source.path, CUT).answer() shouldBe 1.seconds

      rewrite("2.0\n", keepModified = false)

      runtime.syncSampleAtOrBefore(toolchain, source.path, CUT).answer() shouldBe 2.seconds
    }

  @Test
  fun `a sync sample is read again once the file changes size under the same modified time`() =
    runTest {
      source.writeText("1.0\n")
      runtime.syncSampleAtOrBefore(toolchain, source.path, CUT).answer() shouldBe 1.seconds

      rewrite("2.50\n", keepModified = true)

      runtime.syncSampleAtOrBefore(toolchain, source.path, CUT).answer() shouldBe 2.5.seconds
    }

  @Test
  fun `packets are read once for a file left alone`() =
    runTest {
      source.writeText("0.0,K__\n")

      packets() shouldBe listOf(Duration.ZERO)
      packets() shouldBe listOf(Duration.ZERO)

      runtime.copyPacketSpawns shouldBe 1
    }

  @Test
  fun `packets are read again once the file is written at the same size`() =
    runTest {
      source.writeText("0.0,K__\n")
      packets() shouldBe listOf(Duration.ZERO)

      rewrite("1.0,K__\n", keepModified = false)

      packets() shouldBe listOf(1.seconds)
    }

  @Test
  fun `packets are read again once the file changes size under the same modified time`() =
    runTest {
      source.writeText("0.0,K__\n")
      packets() shouldBe listOf(Duration.ZERO)

      rewrite("0.0,K__\n0.5,___\n", keepModified = true)

      packets() shouldBe listOf(Duration.ZERO, 0.5.seconds)
    }

  // A preview scrubbing the trim end reads a fresh window on every move, and none of the old ones is asked for again.
  @Test
  fun `only the latest window read for a path is held`() =
    runTest {
      source.writeText("0.0,K__\n")
      packets() shouldBe listOf(Duration.ZERO)

      packets(to = CUT * 2) shouldBe listOf(Duration.ZERO)
      runtime.heldCopyPacketReads() shouldBe 1
      packets() shouldBe listOf(Duration.ZERO)

      runtime.copyPacketSpawns shouldBe 3
    }

  @Test
  fun `a reprobe drops the packets read off the file it replaced`() =
    runTest {
      source.writeText("0.0,K__\n")
      packets() shouldBe listOf(Duration.ZERO)

      runtime.reprobe(toolchain, source.path)
      packets() shouldBe listOf(Duration.ZERO)

      runtime.copyPacketSpawns shouldBe 2
    }

  @Test
  fun `a probe is read again once the file is written at the same size`() =
    runTest {
      source.writeText(report(width = 320))
      runtime
        .probe(toolchain, source.path)
        .answer()
        ?.video
        ?.codedSize
        ?.width shouldBe 320

      rewrite(report(width = 640), keepModified = false)

      runtime
        .probe(toolchain, source.path)
        .answer()
        ?.video
        ?.codedSize
        ?.width shouldBe 640
    }

  @Test
  fun `a probe is read again once the file changes size under the same modified time`() =
    runTest {
      source.writeText(report(width = 320))
      runtime
        .probe(toolchain, source.path)
        .answer()
        ?.video
        ?.codedSize
        ?.width shouldBe 320

      rewrite(report(width = 1280), keepModified = true)

      runtime
        .probe(toolchain, source.path)
        .answer()
        ?.video
        ?.codedSize
        ?.width shouldBe 1280
    }

  // The colour reads come off the probe's own spawn, so they have to move with it rather than keep the old file's.
  @Test
  fun `the colour reads follow a file rewritten at the same path`() =
    runTest {
      source.writeText(report(pixelFormat = "yuv420p", matrix = "bt709"))
      runtime.probe(toolchain, source.path)
      runtime.colorRead(toolchain, source.path).answer()?.decodesToTailFormat shouldBe true
      runtime.colorRead(toolchain, source.path).answer()?.isTaggedBt709 shouldBe true

      rewrite(report(pixelFormat = "yuv422p", matrix = "bt709"), keepModified = false)
      runtime.colorRead(toolchain, source.path).answer()?.decodesToTailFormat shouldBe false

      rewrite(report(pixelFormat = "yuv422p", matrix = "bt2020nc"), keepModified = true)
      runtime.colorRead(toolchain, source.path).answer()?.isTaggedBt709 shouldBe false
    }

  // What ffprobe prints for a one-second video stream, as the probe reads it.
  private fun report(
    width: Int = 320,
    pixelFormat: String = "yuv420p",
    matrix: String = "bt709",
  ): String =
    """
    [STREAM]
    codec_type=video
    codec_name=h264
    width=$width
    height=240
    pix_fmt=$pixelFormat
    color_space=$matrix
    [/STREAM]
    [FORMAT]
    duration=1.000000
    [/FORMAT]
    """.trimIndent()

  private suspend fun packets(to: Duration = CUT): List<Duration> =
    runtime
      .copyPackets(toolchain, source.path, Duration.ZERO, to)
      .shouldBeInstanceOf<CopyPacketRead.Read>()
      .packets
      .map { it.pts }

  // Writes [content] over the source. A rewrite inside one tick of the file system's clock keeps its modified time, so
  // a fresh one is set a whole minute on, or the old one put back when [keepModified] holds it. The time is carried as
  // a FileTime, since milliseconds would drop the nanoseconds the stamp reads and move it on their own.
  private fun rewrite(
    content: String,
    keepModified: Boolean,
  ) {
    val path = source.toPath()
    val modified = Files.getLastModifiedTime(path)
    source.writeText(content)
    val restored = if (keepModified) modified else FileTime.from(modified.toInstant().plusSeconds(MINUTE_SECONDS))
    Files.setLastModifiedTime(path, restored)
    Files.getLastModifiedTime(path) shouldBe restored
  }

  // An ffprobe that prints the file named last on its command line, whatever else it is asked.
  private fun toolchain(): Toolchain {
    val ffprobe =
      File(directory, "ffprobe").apply {
        writeText("#!/bin/sh\nfor last in \"$@\"; do :; done\ncat \"\$last\"\n")
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

  private companion object {
    val CUT = 5.seconds
    const val MINUTE_SECONDS = 60L
  }
}
