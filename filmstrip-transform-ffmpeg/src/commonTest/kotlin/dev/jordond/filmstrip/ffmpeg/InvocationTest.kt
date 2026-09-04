package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.InputSpec
import dev.jordond.filmstrip.ffmpeg.internal.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.arguments
import dev.jordond.filmstrip.ffmpeg.internal.ffmpegEncoderNamed
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * What reaches the command line, for the parts the planner is meant to have settled already.
 */
class InvocationTest {
  // The name comes off the capability the planner picked, so a build where the device resolved a
  // hardware encoder runs that one rather than a default derived from the codec alone.
  @Test
  fun `runs the encoder the plan resolved rather than one derived from the codec`() {
    argumentsFor(VideoCodec.H264, "libx264").encoderName() shouldBe "libx264"
    argumentsFor(VideoCodec.Hevc, "hevc_videotoolbox").encoderName() shouldBe "hevc_videotoolbox"
  }

  // Every device this backend builds names an encoder for the codecs it reports, so a plan with a
  // video track and no name means a hand-built device. Falling through to libx264 would encode
  // H264 into a file the caller asked for something else in and never say so.
  @Test
  fun `a plan that names no encoder is refused rather than encoded as H264`() {
    assertFailsWith<IllegalStateException> { argumentsFor(VideoCodec.Vp9, null) }
  }

  // ffmpeg only ever names libx264, libx265, hevc_videotoolbox and the like for video, and aac or
  // alac for audio, so an audio codec reaching here that is neither means the plan resolved to
  // something this backend cannot encode. Falling through to aac would write the wrong codec into
  // the file without saying so.
  @Test
  fun `a plan with an unencodable audio codec is refused rather than encoded as aac`() {
    assertFailsWith<IllegalStateException> { audioArgumentsFor(AudioCodec.Opus) }
  }

  // -preset belongs to x264 and x265. VideoToolbox warns that the AVOption went unused and encodes
  // anyway, which is a line of noise in every export's stderr for an option that does nothing.
  @Test
  fun `passes a preset only to the encoders that have one`() {
    argumentsFor(VideoCodec.H264, "libx264") shouldContain "-preset"
    argumentsFor(VideoCodec.Hevc, "hevc_videotoolbox").shouldNotContain("-preset")
  }

  @Test
  fun `tags HEVC as hvc1 whichever encoder writes it`() {
    argumentsFor(VideoCodec.Hevc, "libx265").after("-tag:v") shouldBe "hvc1"
    argumentsFor(VideoCodec.Hevc, "hevc_videotoolbox").after("-tag:v") shouldBe "hvc1"
    argumentsFor(VideoCodec.H264, "libx264").shouldNotContain("-tag:v")
  }

  // yuv420p is what every SDR export writes today, whichever encoder runs it, and a grade reaching
  // an encoder with no HDR profile of its own has nowhere else to fall back to.
  @Test
  fun `writes plain yuv420p when there is no grade to carry`() {
    argumentsFor(VideoCodec.Hevc, "hevc_videotoolbox").after("-pix_fmt") shouldBe "yuv420p"
    argumentsFor(VideoCodec.H264, "libx264", HdrTransfer.Pq).after("-pix_fmt") shouldBe "yuv420p"
  }

  // The pixel format and profile come off the encoder table this backend already keeps, so a test
  // pinning a copy of them here would drift silently the day that table changes.
  @Test
  fun `writes the encoder's own 10-bit pixel format and profile for an HDR encode`() {
    val encoder = checkNotNull(ffmpegEncoderNamed("libx265"))
    val arguments = argumentsFor(VideoCodec.Hevc, "libx265", HdrTransfer.Pq)

    arguments.after("-pix_fmt") shouldBe encoder.hdrPixelFormat
    arguments.after("-profile:v") shouldBe encoder.hdrProfile
  }

  @Test
  fun `tags PQ as smpte2084 and never HLG's tag`() {
    val arguments = argumentsFor(VideoCodec.Hevc, "libx265", HdrTransfer.Pq)

    arguments.after("-color_trc") shouldBe "smpte2084"
    arguments.shouldNotContain("arib-std-b67")
    arguments.after("-color_primaries") shouldBe "bt2020"
    arguments.after("-colorspace") shouldBe "bt2020nc"
  }

  @Test
  fun `tags HLG as arib-std-b67 rather than assuming PQ`() {
    val arguments = argumentsFor(VideoCodec.Hevc, "libx265", HdrTransfer.Hlg)

    arguments.after("-color_trc") shouldBe "arib-std-b67"
    arguments.shouldNotContain("smpte2084")
  }

  // x265 does not read the container-level colour tags back out of its own bitstream, so HDR10 has
  // to be spelled again in its own params. hevc_videotoolbox has no such gap, so it stays untouched.
  @Test
  fun `repeats HDR10 signalling in x265-params only for libx265`() {
    argumentsFor(VideoCodec.Hevc, "libx265", HdrTransfer.Pq).after("-x265-params") shouldBe
      "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:hdr10=1:repeat-headers=1"
    argumentsFor(VideoCodec.Hevc, "hevc_videotoolbox", HdrTransfer.Pq).shouldNotContain("-x265-params")
  }

  // AudioSpec.AudioOnly lowers to a plan with no videoLabel, which is what audioArgumentsFor
  // already builds. This is the shape that reaches the command line: -vn instead of a video map,
  // and no -c:v at all since there is no encoder to name for a track that is not written.
  // Only ffmpeg 7 and newer carries the tone-map nodes' own BT.709 attributes onto the written
  // stream, so the flags are what make the file read the same on every build.
  @Test
  fun `tags a tone-mapped output as BT709 rather than leaving the matrix unwritten`() {
    val arguments = argumentsFor(VideoCodec.H264, "libx264", toneMapped = true)

    arguments.after("-color_primaries") shouldBe "bt709"
    arguments.after("-colorspace") shouldBe "bt709"
    arguments.after("-color_trc") shouldBe "bt709"
  }

  // A grade that is kept writes BT.2020, so the tone-map tags must not reach it, and an ordinary
  // SDR export carries the source's own attributes rather than being relabelled BT.709.
  @Test
  fun `leaves colour tags alone when nothing was tone-mapped`() {
    argumentsFor(VideoCodec.H264, "libx264").shouldNotContain("-color_primaries")
    argumentsFor(VideoCodec.Hevc, "libx265", HdrTransfer.Pq, toneMapped = true)
      .after("-color_primaries") shouldBe "bt2020"
  }

  @Test
  fun `an audio-only plan writes -vn rather than mapping a video stream`() {
    val arguments = audioArgumentsFor(AudioCodec.Aac)

    arguments shouldContain "-vn"
    arguments.shouldNotContain("-c:v")
    arguments.after("-map") shouldBe "[a]"
  }

  // libvpx picks CRF 32 on its own when no bitrate is set and prints a line saying so. Asking for
  // it spells out what the export is doing, and the zeroed bitrate is what reaches that mode.
  @Test
  fun `asks VP9 for constant quality when the plan carries no bitrate`() {
    val arguments = argumentsFor(VideoCodec.Vp9, "libvpx-vp9")

    arguments.after("-crf") shouldBe "32"
    arguments.after("-b:v") shouldBe "0"
  }

  // The maps are optional because ffmpeg refuses one that matches no stream, and a source with no
  // audio track is still one this backend copies.
  @Test
  fun `a copy maps both streams as optional by index and copies them rather than naming an encoder`() {
    val arguments = copyArguments()

    arguments.allAfter("-map") shouldBe listOf("0:v?", "0:a?")
    arguments.after("-c") shouldBe "copy"
  }

  @Test
  fun `a copy runs no filter graph`() {
    copyArguments().shouldNotContain("-filter_complex")
  }

  @Test
  fun `a copy names no codec of its own`() {
    val arguments = copyArguments()

    arguments.shouldNotContain("-c:v")
    arguments.shouldNotContain("-c:a")
  }

  // A node names its file by placeholder, because where the bytes land is not known until the
  // backend has a scratch directory. The path is escaped on the way in, since a directory is free
  // to carry a character the graph's own punctuation uses.
  @Test
  fun `swaps a sidecar's placeholder for the escaped path it was written to`() {
    val sidecar = Sidecar(CUBE.encodeToByteArray(), "cube")
    val arguments =
      Invocation(
        inputs = listOf(InputSpec(InputSource.OfPath("/fixtures/a.mp4"))),
        filterGraph = "[0:v]lut3d=file=${sidecar.placeholder}[v]",
        videoLabel = "v",
        audioLabel = null,
        output =
          OutputFormat(
            size = Size(1280, 720),
            videoCodec = VideoCodec.H264,
            audioCodec = AudioCodec.None,
            bitrate = null,
            frameRate = 30,
            audioFormat = null,
          ),
        videoEncoder = "libx264",
        duration = 2.seconds,
        sidecars = listOf(sidecar),
      ).arguments(TOOLCHAIN, FfmpegConfig(), listOf("/fixtures/a.mp4"), listOf(SIDECAR_PATH), "/out.mp4")

    arguments.after("-filter_complex") shouldBe "[0:v]lut3d=file=/scratch/grade\\\\:1/asset0.cube[v]"
  }

  // Ahead of -i, which is the seek the demuxer performs. After -i ffmpeg decodes its way to the
  // cut and throws the frames away, which is the whole cost a snapped trim exists to avoid.
  @Test
  fun `a windowed copy seeks and bounds ahead of the input rather than after it`() {
    val arguments = copyArguments(startSeconds = 4.5, durationSeconds = 2.75)

    arguments.after("-ss") shouldBe "4.500000"
    arguments.after("-t") shouldBe "2.750000"
    arguments.indexOf("-ss") shouldBeLessThan arguments.indexOf("-i")
    arguments.indexOf("-t") shouldBeLessThan arguments.indexOf("-i")
  }

  @Test
  fun `an untrimmed copy carries no window at all`() {
    copyArguments().shouldNotContain("-ss")
    copyArguments().shouldNotContain("-t")
  }

  @Test
  fun `a copy still reports progress and moves the moov atom to the front`() {
    val arguments = copyArguments()

    arguments.after("-progress") shouldBe "pipe:1"
    arguments.after("-movflags") shouldBe "+faststart"
  }

  private fun List<String>.encoderName(): String = after("-c:v")

  private fun List<String>.after(flag: String): String = this[indexOf(flag) + 1]

  private fun List<String>.allAfter(flag: String): List<String> =
    withIndex().filter { it.value == flag }.map { this[it.index + 1] }

  private fun copyArguments(
    startSeconds: Double? = null,
    durationSeconds: Double? = null,
  ): List<String> =
    Invocation(
      inputs =
        listOf(
          InputSpec(
            source = InputSource.OfPath("/fixtures/a.mp4"),
            durationSeconds = durationSeconds,
            startSeconds = startSeconds,
          ),
        ),
      filterGraph = "",
      videoLabel = null,
      audioLabel = null,
      output =
        OutputFormat(
          size = Size(1280, 720),
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      videoEncoder = null,
      duration = 2.seconds,
      copy = true,
    ).arguments(TOOLCHAIN, FfmpegConfig(), listOf("/fixtures/a.mp4"), emptyList(), "/out.mp4")

  private fun audioArgumentsFor(codec: AudioCodec): List<String> =
    Invocation(
      inputs = listOf(InputSpec(InputSource.OfPath("/fixtures/a.mp4"))),
      filterGraph = "[0:a]anull[a]",
      videoLabel = null,
      audioLabel = "a",
      output =
        OutputFormat(
          size = Size(1280, 720),
          videoCodec = VideoCodec.Auto,
          audioCodec = codec,
          bitrate = null,
          frameRate = 30,
          audioFormat = AudioFormat(sampleRate = 48_000, channelCount = 2),
        ),
      videoEncoder = null,
      duration = 2.seconds,
    ).arguments(TOOLCHAIN, FfmpegConfig(), listOf("/fixtures/a.mp4"), emptyList(), "/out.mp4")

  private fun argumentsFor(
    codec: VideoCodec,
    encoder: String?,
    hdrTransfer: HdrTransfer? = null,
    toneMapped: Boolean = false,
  ): List<String> =
    Invocation(
      inputs = listOf(InputSpec(InputSource.OfPath("/fixtures/a.mp4"))),
      filterGraph = "[0:v]null[v]",
      videoLabel = "v",
      audioLabel = null,
      output =
        OutputFormat(
          size = Size(1280, 720),
          videoCodec = codec,
          audioCodec = AudioCodec.None,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      videoEncoder = encoder,
      duration = 2.seconds,
      hdrTransfer = hdrTransfer,
      toneMapped = toneMapped,
    ).arguments(TOOLCHAIN, FfmpegConfig(), listOf("/fixtures/a.mp4"), emptyList(), "/out.mp4")

  private companion object {
    const val CUBE = "LUT_3D_SIZE 2"

    // A colon in the directory, because that is the character a filter graph reads as the end of an
    // argument.
    const val SIDECAR_PATH = "/scratch/grade:1/asset0.cube"

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
