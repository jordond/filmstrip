package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSpec
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.arguments
import dev.jordond.filmstrip.ffmpeg.internal.process.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.encode.resolveVideoBitrate
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The one `-x265-params` option an encode ends on, from filmstrip's own keys and a caller's extra arguments.
 */
class X265ParamsTest {
  // x265 does not read the container-level color tags back out of its own bitstream, so HDR10 has
  // to be spelled again in its own params. hevc_videotoolbox has no such gap, so it stays untouched.
  @Test
  fun `repeats HDR10 signalling in x265-params only for libx265`() {
    argumentsFor(VideoCodec.Hevc, "libx265", HdrTransfer.Pq).after("-x265-params") shouldBe
      "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:hdr10=1:repeat-headers=1"
    argumentsFor(VideoCodec.Hevc, "hevc_videotoolbox", HdrTransfer.Pq).shouldNotContain("-x265-params")
  }

  // libx265 4.1 aborts on these widths with cutree on, so the literals pin that encoder's own limit.
  @Test
  fun `turns cutree off for libx265 across the widths it crashes on`() {
    listOf(34, 42, 48).forEach { width ->
      withClue("$width wide") {
        argumentsFor(VideoCodec.Hevc, "libx265", size = Size(width, 720)).after("-x265-params") shouldBe "cutree=0"
      }
    }
  }

  @Test
  fun `leaves cutree on for libx265 either side of the widths it crashes on`() {
    listOf(32, 50).forEach { width ->
      withClue("$width wide") {
        argumentsFor(VideoCodec.Hevc, "libx265", size = Size(width, 720)).shouldNotContain("-x265-params")
      }
    }
  }

  @Test
  fun `a caller's x265-params go ahead of the HDR keys inside the one option`() {
    val arguments =
      argumentsFor(
        VideoCodec.Hevc,
        "libx265",
        HdrTransfer.Pq,
        extraArgs = listOf("-x265-params", "crf=18", "-metadata", "title=x", "-x265-params", "aq-mode=3"),
      )

    arguments.count { it == "-x265-params" } shouldBe 1
    arguments.after("-x265-params") shouldBe
      "crf=18:aq-mode=3:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:hdr10=1:repeat-headers=1"
    arguments.takeLast(3) shouldBe listOf("-metadata", "title=x", "/out.mp4")
  }

  // 40 is the middle of the widths libx265 4.1 crashes on, so the literal pins that encoder's own limit.
  @Test
  fun `a caller's cutree on a width libx265 crashes on still ends on cutree off`() {
    val arguments =
      argumentsFor(
        VideoCodec.Hevc,
        "libx265",
        HdrTransfer.Pq,
        size = Size(40, 720),
        extraArgs = listOf("-x265-params", "cutree=1"),
      )

    arguments.count { it == "-x265-params" } shouldBe 1
    arguments.after("-x265-params") shouldBe
      "cutree=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:hdr10=1:repeat-headers=1:cutree=0"
  }

  @Test
  fun `a caller's x265-params stay as they came when filmstrip writes none`() {
    val extra = listOf("-x265-params", "crf=18")

    listOf(
      argumentsFor(VideoCodec.Hevc, "libx265", extraArgs = extra),
      argumentsFor(VideoCodec.Hevc, "hevc_videotoolbox", HdrTransfer.Pq, extraArgs = extra),
      argumentsFor(VideoCodec.H264, "libx264", size = Size(40, 720), extraArgs = extra),
    ).forEach { arguments ->
      arguments.count { it == "-x265-params" } shouldBe 1
      arguments.takeLast(3) shouldBe extra + "/out.mp4"
    }
  }

  @Test
  fun `a trailing x265-params flag with no value is left in the tail`() {
    val arguments = argumentsFor(VideoCodec.Hevc, "libx265", HdrTransfer.Pq, extraArgs = listOf("-x265-params"))

    arguments.after("-x265-params") shouldBe
      "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:hdr10=1:repeat-headers=1"
    arguments.takeLast(2) shouldBe listOf("-x265-params", "/out.mp4")
  }

  @Test
  fun `merges cutree into the one x265-params an HDR encode writes`() {
    val arguments = argumentsFor(VideoCodec.Hevc, "libx265", HdrTransfer.Pq, size = Size(42, 42))

    arguments.count { it == "-x265-params" } shouldBe 1
    arguments.after("-x265-params") shouldBe
      "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:hdr10=1:repeat-headers=1:cutree=0"
  }

  @Test
  fun `gives other encoders no x265 params at a width libx265 crashes on`() {
    argumentsFor(VideoCodec.Hevc, "hevc_videotoolbox", size = Size(42, 42)).shouldNotContain("-x265-params")
    argumentsFor(VideoCodec.H264, "libx264", size = Size(42, 42)).shouldNotContain("-x265-params")
  }

  private fun List<String>.after(flag: String): String = this[indexOf(flag) + 1]

  private fun argumentsFor(
    codec: VideoCodec,
    encoder: String,
    hdrTransfer: HdrTransfer? = null,
    size: Size = Size(1280, 720),
    extraArgs: List<String> = emptyList(),
  ): List<String> =
    Invocation(
      inputs = listOf(InputSpec(InputSource.OfPath("/fixtures/a.mp4"))),
      filterGraph = "[0:v]null[v]",
      videoLabel = "v",
      audioLabel = null,
      output =
        OutputFormat(
          size = size,
          videoCodec = codec,
          audioCodec = AudioCodec.None,
          bitrate = resolveVideoBitrate(VideoQuality.High, VideoCodec.H264, Size(1280, 720), 30),
          frameRate = 30,
          audioFormat = null,
        ),
      videoEncoder = encoder,
      duration = 2.seconds,
      hdrTransfer = hdrTransfer,
    ).arguments(TOOLCHAIN, FfmpegConfig(extraArgs = extraArgs), listOf("/fixtures/a.mp4"), emptyList(), "/out.mp4")

  private companion object {
    val TOOLCHAIN =
      Toolchain(
        ffmpeg = "ffmpeg",
        ffprobe = "ffprobe",
        version = FfmpegVersion(banner = "ffmpeg version 9.0.1", major = 9, minor = 0),
        filters = emptySet(),
        encoders = setOf("libx264", "libx265"),
      )
  }
}
