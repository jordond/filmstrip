package dev.jordond.filmstrip.convention.testmedia

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import kotlin.test.Test

class GenerateTestMediaArgsTest {
  private val task =
    ProjectBuilder
      .builder()
      .withProjectDir(Files.createTempDirectory("generate").toFile())
      .build()
      .tasks
      .register("generate", GenerateTestMediaTask::class.java)
      .get()

  private val sdr = spec("sdr")

  private val hdr = sdr.copy(name = "hdr", bitrateKbps = 6_000, transfer = FixtureTransfer.Pq)

  private val rotated = sdr.copy(name = "rotated", rotationDegrees = 90)

  @Test
  fun `every spec carries the noise seed preset and bitrate`() {
    listOf(sdr, hdr, rotated).forEach { spec ->
      val args = task.ffmpegArgs(spec, "out.mp4")

      args.single { it.contains("noise=") } shouldContain "all_seed=20260819"
      args.shouldContainRun("-preset", "medium", "-b:v", "${spec.bitrateKbps}k")
      args.last() shouldBe "out.mp4"
    }
  }

  @Test
  fun `only an mp4 spec moves its index to the front`() {
    val matroska = sdr.copy(name = "matroska", container = FixtureContainer.Matroska)

    task.ffmpegArgs(sdr, "out.mp4").shouldContainRun("-movflags", "+faststart")
    task.ffmpegArgs(matroska, "out.mkv").none { it == "-movflags" } shouldBe true
    matroska.fileName shouldBe "matroska.mkv"
  }

  @Test
  fun `a transport stream spec is written under its own extension with no mp4 flags`() {
    val stream = sdr.copy(name = "stream", container = FixtureContainer.MpegTs)

    task.ffmpegArgs(stream, "out.ts").none { it == "-movflags" } shouldBe true
    stream.fileName shouldBe "stream.ts"
  }

  @Test
  fun `a click spec carries silence with one click in place of the tone`() {
    val args = task.ffmpegArgs(sdr.copy(name = "click", clickSeconds = 0.25), "out.mp4")

    args.shouldContainRun("-i", "aevalsrc=exprs=if(eq(n\\,12000)\\,0.5\\,0):sample_rate=48000:duration=1.0")
    args.none { it.startsWith("sine=") } shouldBe true
  }

  @Test
  fun `a single audio track is left to ffmpeg's own stream selection`() {
    val args = task.ffmpegArgs(sdr, "out.mp4")

    args.none { it == "-map" || it.startsWith("-disposition") || it.startsWith("-metadata:s") } shouldBe true
    args.count { it.startsWith("sine=") } shouldBe 1
  }

  @Test
  fun `several audio tracks each get a tone a language and a default flag`() {
    val tracks =
      listOf(
        AudioTrackSpec(toneHz = 440, language = "eng"),
        AudioTrackSpec(toneHz = 660, language = "fra", isDefault = true),
        AudioTrackSpec(toneHz = 990),
      )
    val args = task.ffmpegArgs(sdr.copy(name = "several", audioTracks = tracks), "out.mp4")

    args.filter { it.startsWith("sine=") }.map { it.substringAfter("frequency=").substringBefore(":") } shouldBe
      listOf("440", "660", "990")
    args.shouldContainRun("-map", "0:v", "-map", "1:a", "-map", "2:a", "-map", "3:a")
    args.shouldContainRun("-metadata:s:a:0", "language=eng", "-disposition:a:0", "0")
    args.shouldContainRun("-metadata:s:a:1", "language=fra", "-disposition:a:1", "default")
    args.shouldContainRun("-disposition:a:2", "0")
    args.none { it == "-metadata:s:a:2" } shouldBe true
  }

  @Test
  fun `every pass runs on one thread with bitexact output and no metadata`() {
    val passes =
      listOf(sdr, hdr, rotated).map { task.ffmpegArgs(it, "out.mp4") } +
        listOf(task.rotateArgs(rotated, "in", "out.mp4"))

    passes.forEach { args ->
      args.shouldContainRun("-cpuflags", "0")
      args.shouldContainRun("-filter_threads", "1")
      args.shouldContainRun("-threads", "1")
      args.shouldContainRun("-fflags", "+bitexact")
      args.shouldContainRun("-flags:v", "+bitexact")
      args.shouldContainRun("-flags:a", "+bitexact")
      args.shouldContainRun("-map_metadata", "-1")
      args.last() shouldBe "out.mp4"
    }
  }

  @Test
  fun `an sdr spec encodes eight bit avc on one thread with no assembly`() {
    val args = task.ffmpegArgs(sdr, "out.mp4")

    args.shouldContainRun("-c:v", "libx264", "-pix_fmt", "yuv420p")
    args.shouldContainRun("-x264-params", "threads=1:lookahead-threads=1:asm=0")
  }

  @Test
  fun `an hdr spec encodes ten bit hevc on one pool thread`() {
    val args = task.ffmpegArgs(hdr, "out.mp4")

    args.shouldContainRun("-c:v", "libx265", "-pix_fmt", "yuv420p10le")
    val x265 = args[args.indexOf("-x265-params") + 1].split(":")
    x265.shouldContainRun("pools=1", "frame-threads=1")
  }

  @Test
  fun `an hdr spec leaves the host details out of the x265 sei`() {
    FixtureTransfer.entries.forEach { transfer ->
      val args = task.ffmpegArgs(hdr.copy(transfer = transfer), "out.mp4")

      args[args.indexOf("-x265-params") + 1].split(":") shouldContain "info=0"
    }
  }

  @Test
  fun `an encoder probe writes one frame to stdout on the c paths`() {
    val args = encoderProbeArgs("libx265", "yuv420p10le", "hevc")

    args.shouldContainRun("-cpuflags", "0")
    args.shouldContainRun("-frames:v", "1", "-c:v", "libx265", "-pix_fmt", "yuv420p10le", "-f", "hevc", "-")
    args.last() shouldBe "-"
  }

  @Test
  fun `the rotate pass copies the streams under a display matrix`() {
    task
      .rotateArgs(
        rotated,
        "in.mp4",
        "out.mp4",
      ).shouldContainRun("-display_rotation", "90", "-i", "in.mp4", "-c", "copy")
  }
}
