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
import dev.jordond.filmstrip.ffmpeg.internal.previewArguments
import dev.jordond.filmstrip.ffmpeg.internal.previewFrameBytes
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the pump puts on the command line, and what it shares with the export that wrote the graph.
 *
 * The first test is the parity claim on this backend at the level a second graph builder would
 * break it: one lowering, two argument lists, one filter graph between them.
 */
class PreviewInvocationTest {
  @Test
  fun `runs the same filter graph the export runs, byte for byte`() {
    val invocation = graphed()

    val exported = invocation.arguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), "/out.mp4")
    val previewed = invocation.previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), 400.milliseconds)

    previewed.after("-filter_complex") shouldBe exported.after("-filter_complex")
  }

  // -ss is an input option, so it only means "seek before decoding" ahead of the -i it belongs to.
  @Test
  fun `seeks the first input before opening it`() {
    val previewed = previewArgumentsFor(400.milliseconds)

    previewed.indexOf("-ss") shouldBe previewed.indexOf("-i") - 2
    previewed.after("-ss") shouldBe "0.900000"
  }

  // Without these ffmpeg rebases the seeked input to zero and every trim the graph carries moves
  // with it, so the frames come back from the wrong part of the clip.
  @Test
  fun `keeps the source timestamps in front of the graph`() {
    previewArgumentsFor(400.milliseconds) shouldContain "-copyts"
    previewArgumentsFor(400.milliseconds) shouldContain "-start_at_zero"
  }

  // A clip that opens on its source's first frame has nothing to seek past at composition time
  // zero, and -ss 0 would only cost a seek that lands where the demuxer already is.
  @Test
  fun `opens an untrimmed clip without a seek at all`() {
    graphed(seekBase = Duration.ZERO)
      .previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), Duration.ZERO)
      .shouldNotContain("-ss")
  }

  // The clip is trimmed, so composition time zero is already 500ms into the source.
  @Test
  fun `opens a trimmed clip at the source time its trim starts on`() {
    previewArgumentsFor(Duration.ZERO).after("-ss") shouldBe "0.500000"
  }

  // A composition the graph cannot window carries no seek base, and reading it forward from the
  // start is the only correct answer left.
  @Test
  fun `runs unseeked when the graph cannot be windowed`() {
    graphed(seekBase = null)
      .previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), 400.milliseconds)
      .shouldNotContain("-ss")
  }

  // An overlay image seeked into delivers nothing at all, so its branch is moved to the scrub
  // instead. The offset is the composition time, not the source time the clip is seeked to, because
  // the graph's own clock is what a sendcmd sidecar's intervals are written against.
  @Test
  fun `carries a rebased branch to the scrub rather than seeking it`() {
    val previewed =
      overlaid().previewArguments(
        TOOLCHAIN,
        FfmpegConfig(),
        OVERLAY_INPUTS,
        emptyList(),
        400.milliseconds,
      )

    previewed.allAfter("-ss") shouldBe listOf("0.900000")
    previewed.allAfter("-itsoffset") shouldBe listOf("0.400000")
    // An input option, so it only moves the input it sits ahead of: past the clip's own -i, and
    // ahead of the image's.
    val offset = previewed.indexOf("-itsoffset")
    assertTrue(offset > previewed.indexOf("-i") && offset < previewed.lastIndexOf("-i"), previewed.toString())
  }

  // The seek delivers the first frame at or after where it was asked for, so a scrub landing inside
  // a frame period opens the clip on the frame after it. The branch is moved onto that frame rather
  // than onto the scrub, because the sidecar's intervals were sampled on the same grid. The seek
  // itself is not snapped: ffmpeg is already doing that, and it is the source's grid it does it on.
  @Test
  fun `snaps a branch onto the frame the seek lands on`() {
    val previewed = overlaid().previewArguments(TOOLCHAIN, FfmpegConfig(), OVERLAY_INPUTS, emptyList(), OFF_GRID)

    previewed.after("-ss") shouldBe "1.010000"
    previewed.after("-itsoffset") shouldBe "0.533333"
  }

  // The offset only cancels where the branch ends on a setpts back to its own first frame. Moving
  // one that does not would hand the merge frames the clip's have no timestamp in common with.
  @Test
  fun `leaves a branch that is not rebased at its own start`() {
    val previewed =
      overlaid(rebased = false)
        .previewArguments(TOOLCHAIN, FfmpegConfig(), OVERLAY_INPUTS, emptyList(), 400.milliseconds)

    previewed.shouldNotContain("-itsoffset")
  }

  // Nothing is scrubbed past, so nothing needs moving, and an offset of zero is a flag that says so
  // and costs a parse.
  @Test
  fun `offsets no branch for a preview opening at the head`() {
    overlaid()
      .previewArguments(TOOLCHAIN, FfmpegConfig(), OVERLAY_INPUTS, emptyList(), Duration.ZERO)
      .shouldNotContain("-itsoffset")
  }

  @Test
  fun `writes raw RGBA frames to the pipe rather than a file`() {
    val previewed = previewArgumentsFor(Duration.ZERO)

    previewed.allAfter("-f").last() shouldBe "rawvideo"
    previewed.last() shouldBe "pipe:1"
    previewed.after("-pix_fmt") shouldBe "rgba"
    previewed.after("-fps_mode") shouldBe "passthrough"
  }

  // -progress and +faststart belong to a run that writes a container and reports how far through it
  // is. A pump writes neither.
  @Test
  fun `carries none of the export's own output flags`() {
    val previewed = previewArgumentsFor(Duration.ZERO)

    previewed.shouldNotContain("-progress")
    previewed.shouldNotContain("-movflags")
    previewed.shouldNotContain("-c:v")
    previewed.shouldNotContain("-vsync")
  }

  // ffmpeg refuses a graph whose output nothing reads, so the branch this backend does not monitor
  // still has to go somewhere.
  @Test
  fun `maps the audio branch into a muxer that writes nothing`() {
    val previewed = previewArgumentsFor(Duration.ZERO)

    previewed.allAfter("-map") shouldBe listOf("[a]", "[v]")
    previewed.allAfter("-f") shouldBe listOf("null", "rawvideo")
  }

  @Test
  fun `maps the source stream directly for a transmux`() {
    val previewed = copied().previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), Duration.ZERO)

    previewed.after("-map") shouldBe "0:v:0"
    previewed.after("-s") shouldBe "${OUTPUT.size.width}x${OUTPUT.size.height}"
    previewed.shouldNotContain("-filter_complex")
  }

  // The pump writes the same files into its own scratch directory, so a node naming one by
  // placeholder reaches a real path here as well as on the export.
  @Test
  fun `swaps a sidecar's placeholder for the escaped path the pump wrote it to`() {
    val sidecar = Sidecar(CUBE.encodeToByteArray(), "cube")
    val invocation =
      Invocation(
        inputs = listOf(InputSpec(InputSource.OfPath("/fixtures/a.mp4"))),
        filterGraph = "[0:v]lut3d=file=${sidecar.placeholder}[v]",
        videoLabel = "v",
        audioLabel = null,
        output = OUTPUT,
        videoEncoder = "libx264",
        duration = 1.seconds,
        seekBase = Duration.ZERO,
        sidecars = listOf(sidecar),
      )

    invocation
      .previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, listOf(SIDECAR_PATH), Duration.ZERO)
      .after("-filter_complex") shouldBe "[0:v]lut3d=file=/scratch/grade\\\\:1/asset0.cube[v]"
  }

  // The frame comes off OUTPUT. Four bytes a pixel is this backend's own wire format, from the rgba
  // the pump asks ffmpeg for, so that one stays a literal.
  @Test
  fun `reads four bytes a pixel off the pipe`() {
    graphed().previewFrameBytes shouldBe OUTPUT.size.width * OUTPUT.size.height * RGBA_BYTES_PER_PIXEL
  }

  private fun previewArgumentsFor(at: Duration): List<String> =
    graphed().previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), at)

  private fun List<String>.after(flag: String): String = this[indexOf(flag) + 1]

  private fun List<String>.allAfter(flag: String): List<String> =
    withIndex().filter { it.value == flag }.map { this[it.index + 1] }

  private fun graphed(seekBase: Duration? = 500.milliseconds): Invocation =
    Invocation(
      inputs = listOf(InputSpec(InputSource.OfPath("/fixtures/a.mp4"))),
      filterGraph = "[0:v]trim=start=0.500000:end=1.500000[v];[0:a]anull[a]",
      videoLabel = "v",
      audioLabel = "a",
      output = OUTPUT,
      videoEncoder = "libx264",
      duration = 1.seconds,
      seekBase = seekBase,
    )

  // A clip with an overlay merged onto it: the image is looped at the output rate and its branch
  // ends on the setpts that [InputSpec.rebased] stands for.
  private fun overlaid(rebased: Boolean = true): Invocation =
    Invocation(
      inputs =
        listOf(
          InputSpec(InputSource.OfPath("/fixtures/a.mp4")),
          InputSpec(
            source = InputSource.OfImage(ImageSource.of("/fixtures/logo.png")),
            durationSeconds = 1.0,
            loopFrameRate = 30f,
            rebased = rebased,
          ),
        ),
      filterGraph = "[0:v]trim=start=0.500000:end=1.500000[v0];[1:v]setpts=expr=PTS-STARTPTS[a0];[v0][a0]overlay[v]",
      videoLabel = "v",
      audioLabel = null,
      output = OUTPUT,
      videoEncoder = "libx264",
      duration = 1.seconds,
      seekBase = 500.milliseconds,
    )

  private fun copied(): Invocation =
    Invocation(
      inputs = listOf(InputSpec(InputSource.OfPath("/fixtures/a.mp4"))),
      filterGraph = "",
      videoLabel = null,
      audioLabel = null,
      output = OUTPUT,
      videoEncoder = null,
      duration = 1.seconds,
      copy = true,
      seekBase = Duration.ZERO,
    )

  private companion object {
    val INPUTS = listOf("/fixtures/a.mp4")
    val OVERLAY_INPUTS = listOf("/fixtures/a.mp4", "/fixtures/logo.png")

    // A third of the way into frame 15 of the output's own 30fps grid, so the frame the seek
    // lands on is frame 16 rather than the one the scrub names.
    val OFF_GRID = 510.milliseconds

    const val CUBE = "LUT_3D_SIZE 2"

    // A colon in the directory, because that is the character a filter graph reads as the end of an
    // argument.
    const val SIDECAR_PATH = "/scratch/grade:1/asset0.cube"

    const val RGBA_BYTES_PER_PIXEL = 4

    val OUTPUT =
      OutputFormat(
        size = Size(1280, 720),
        videoCodec = VideoCodec.H264,
        audioCodec = AudioCodec.Aac,
        bitrate = null,
        frameRate = 30,
        audioFormat = AudioFormat(sampleRate = 48_000, channelCount = 2),
      )

    val TOOLCHAIN =
      Toolchain(
        ffmpeg = "ffmpeg",
        ffprobe = "ffprobe",
        version = FfmpegVersion(banner = "ffmpeg version 9.0.1", major = 9, minor = 0),
        filters = emptySet(),
        encoders = setOf("libx264"),
      )
  }
}
