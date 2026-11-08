package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSpec
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.arguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.formatSeconds
import dev.jordond.filmstrip.ffmpeg.internal.lowering.hdrPixelFormatFor
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewArguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewFrameBytes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewLanding
import dev.jordond.filmstrip.ffmpeg.internal.lowering.writtenPixelFormat
import dev.jordond.filmstrip.ffmpeg.internal.process.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.transform.internal.encode.resolveVideoBitrate
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * What the pump puts on the command line, and what it shares with the export that wrote the graph.
 *
 * The first test is the parity claim on this backend at the level a second graph builder would
 * break it: one lowering, two argument lists, one filter graph between them, which the pump ends on
 * the format the encoder writes.
 */
class PreviewInvocationTest {
  @Test
  fun `runs the export's filter graph byte for byte and ends it on the format the export writes`() {
    val invocation = graphed()

    val exported = invocation.arguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), "/out.mp4")
    val previewed = invocation.previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), 400.milliseconds)

    val written = exported.after("-pix_fmt")
    previewed.after("-filter_complex") shouldBe
      "${exported.after("-filter_complex")};[v]format=pix_fmts=$written[vshown]"
  }

  @Test
  fun `ends a kept grade on the 10-bit format the export writes`() {
    val invocation = graphed(hdrTransfer = HdrTransfer.Pq, encoder = "libx265")

    val exported = invocation.arguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), "/out.mp4")
    val previewed = invocation.previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), Duration.ZERO)

    val written = exported.after("-pix_fmt")
    written shouldBe hdrPixelFormatFor("libx265", HdrTransfer.Pq)
    previewed.after("-filter_complex") shouldEndWith ";[v]format=pix_fmts=$written[vshown]"
  }

  @Test
  fun `moves a kept SD colour after the format the export writes`() {
    val color = FilterNode("colorspace", "all" to "bt709")
    val previewed =
      graphed(previewColor = listOf(color))
        .previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), Duration.ZERO)

    previewed.after("-filter_complex") shouldEndWith
      ";[v]format=pix_fmts=${writtenPixelFormat(null)},colorspace=all=bt709[vshown]"
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
  // the branch's own clock is what the overlay's window gate and frame time expressions read.
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
    withClue(previewed.toString()) {
      (offset > previewed.indexOf("-i") && offset < previewed.lastIndexOf("-i")) shouldBe true
    }
  }

  // A scrub inside a frame period opens on the output slot it lands on, so the seek and the branch's
  // move are both that slot's own time and the first frame the seek delivers is the slot's frame.
  // One scrub rounds down onto its slot and the other rounds up, so neither a floor nor a ceil
  // passes both.
  @Test
  fun `seeks and moves a branch onto the slot a scrub lands on`() {
    listOf(ROUNDS_DOWN, ROUNDS_UP).forEach { at ->
      val landing = slotOf(at)
      val previewed = overlaid().previewArguments(TOOLCHAIN, FfmpegConfig(), OVERLAY_INPUTS, emptyList(), at)

      previewed.after("-ss") shouldBe secondsOf(SEEK_BASE + landing)
      previewed.after("-itsoffset") shouldBe secondsOf(landing)
    }
  }

  // The engine reports where the stream starts off the same landing the arguments seek to, so a
  // player walking the stream counts its frames from the slot the first one actually sits on.
  @Test
  fun `lands a windowed preview on the slot and an unwindowed one at the head`() {
    listOf(ROUNDS_DOWN, ROUNDS_UP, 400.milliseconds).forEach { at ->
      graphed().previewLanding(at) shouldBe slotOf(at)
    }
    graphed(seekBase = null).previewLanding(ROUNDS_UP) shouldBe Duration.ZERO
  }

  // The seek has already eaten the landing out of a copied window, so what is left of it is shorter
  // by the slot's time rather than by the scrub's.
  @Test
  fun `shortens a copied window by the slot the scrub lands on`() {
    val previewed =
      copied(
        window = 1.seconds,
      ).previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), ROUNDS_UP)

    previewed.after("-t") shouldBe secondsOf(1.seconds - slotOf(ROUNDS_UP))
  }

  // A scrub lands on a slot of the composition, and the seek and the window read source time from there.
  @Test
  fun `seeks and shortens through the source a scrub at speed reaches`() {
    listOf(0.7, 1.5).forEach { speed ->
      val previewed =
        copied(window = 3.seconds, seekSpeed = speed)
          .previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), ROUNDS_UP)

      val reached = sourceOffsetAt(slotOf(ROUNDS_UP), speed)
      previewed.after("-ss") shouldBe secondsOf(reached)
      previewed.after("-t") shouldBe secondsOf(3.seconds - reached)
    }
  }

  // A branch moved over the scrub sits on the composition clock, which speed leaves alone.
  @Test
  fun `moves a rebased branch by the composition time a scrub at speed lands on`() {
    val previewed =
      overlaid(seekSpeed = 0.7).previewArguments(TOOLCHAIN, FfmpegConfig(), OVERLAY_INPUTS, emptyList(), ROUNDS_UP)

    previewed.after("-itsoffset") shouldBe secondsOf(slotOf(ROUNDS_UP))
    previewed.after("-ss") shouldBe secondsOf(SEEK_BASE + sourceOffsetAt(slotOf(ROUNDS_UP), 0.7))
  }

  // The frame showing when the window opens can sit any distance ahead of the seek, and only a clip at speed reads it.
  @Test
  fun `only a clip at speed opens on the sync sample ahead of the seek`() {
    val atSpeed =
      overlaid(seekSpeed = 0.7).previewArguments(TOOLCHAIN, FfmpegConfig(), OVERLAY_INPUTS, emptyList(), ROUNDS_UP)
    atSpeed[atSpeed.indexOf("-ss") - 1] shouldBe "-noaccurate_seek"

    previewArgumentsFor(ROUNDS_UP) shouldNotContain "-noaccurate_seek"
    copied(window = 3.seconds, seekSpeed = 0.7)
      .previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), ROUNDS_UP) shouldNotContain "-noaccurate_seek"
  }

  // The graph cuts a clip to its slot count from the first frame it is handed, and a windowed
  // preview hands it the frame on the scrub's slot, so that cut falls past the end of the video. The
  // pump stops on the video's last slot instead, and the bound is an option of the pipe's output
  // rather than of the null muxer the audio goes to.
  @Test
  fun `stops a windowed preview on the last slot the video covers`() {
    listOf(ROUNDS_DOWN, ROUNDS_UP).forEach { at ->
      val previewed = previewArgumentsFor(at)

      previewed.after("-frames:v") shouldBe (VIDEO_SLOTS - outputSlotOf(at, RATE)).toString()
      withClue(previewed.toString()) { (previewed.indexOf("-frames:v") > previewed.lastIndexOf("-map")) shouldBe true }
    }
  }

  // Read forward from the head, the graph's own cuts line up with the clips they were written for.
  @Test
  fun `leaves a preview read from the head to the graph's own cut`() {
    graphed(seekBase = null)
      .previewArguments(TOOLCHAIN, FfmpegConfig(), INPUTS, emptyList(), ROUNDS_UP)
      .shouldNotContain("-frames:v")
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

    previewed.allAfter("-map") shouldBe listOf("[a]", "[vshown]")
    previewed.allAfter("-f") shouldBe listOf("null", "rawvideo")
  }

  @Test
  fun `maps the source stream directly for a copy`() {
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
      .after("-filter_complex")
      .substringBefore(";") shouldBe "[0:v]lut3d=file=/scratch/grade\\\\:1/asset0.cube[v]"
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

  private fun graphed(
    seekBase: Duration? = SEEK_BASE,
    hdrTransfer: HdrTransfer? = null,
    encoder: String = "libx264",
    previewColor: List<FilterNode> = emptyList(),
  ): Invocation =
    Invocation(
      inputs = listOf(InputSpec(InputSource.OfPath("/fixtures/a.mp4"))),
      filterGraph = "[0:v]trim=start=0.500000:end=1.500000[v];[0:a]anull[a]",
      videoLabel = "v",
      audioLabel = "a",
      output = OUTPUT,
      videoEncoder = encoder,
      duration = 1.seconds,
      hdrTransfer = hdrTransfer,
      previewColor = previewColor,
      seekBase = seekBase,
      videoSlots = VIDEO_SLOTS,
    )

  // A clip with an overlay merged onto it: the image is looped at the output rate and its branch
  // ends on the setpts that [InputSpec.rebased] stands for.
  private fun overlaid(
    rebased: Boolean = true,
    seekSpeed: Double = 1.0,
  ): Invocation =
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
      seekBase = SEEK_BASE,
      seekSpeed = seekSpeed,
    )

  private fun slotOf(at: Duration): Duration = outputSlotTime(outputSlotOf(at, RATE), RATE)

  private fun secondsOf(time: Duration): String = formatSeconds(time.toDouble(DurationUnit.SECONDS))

  private fun copied(
    window: Duration? = null,
    seekSpeed: Double = 1.0,
  ): Invocation =
    Invocation(
      inputs =
        listOf(
          InputSpec(InputSource.OfPath("/fixtures/a.mp4"), durationSeconds = window?.toDouble(DurationUnit.SECONDS)),
        ),
      filterGraph = "",
      videoLabel = null,
      audioLabel = null,
      output = OUTPUT,
      videoEncoder = null,
      duration = 1.seconds,
      copy = true,
      seekBase = Duration.ZERO,
      seekSpeed = seekSpeed,
    )

  private companion object {
    val INPUTS = listOf("/fixtures/a.mp4")
    val OVERLAY_INPUTS = listOf("/fixtures/a.mp4", "/fixtures/logo.png")

    // The output's rate, which is the grid a scrub lands on.
    const val RATE = 30

    // The slots the graphed clip's one second covers on that grid.
    val VIDEO_SLOTS = outputSlotOf(1.seconds, RATE)

    // Where the overlaid clip's trim opens, which composition time zero maps to.
    val SEEK_BASE = 500.milliseconds

    // A third of the way into a frame period of the output's grid, and three quarters of the way
    // into one, so one lands on the slot it sits in and the other on the slot after it.
    val ROUNDS_DOWN = 510.milliseconds
    val ROUNDS_UP = 525.milliseconds

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
        bitrate = resolveVideoBitrate(VideoQuality.High, VideoCodec.H264, Size(1280, 720), 30),
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
