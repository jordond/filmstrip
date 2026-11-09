package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSpec
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.arguments
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_BITRATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.tenBitCodesAt
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Real libx265 encodes at a width inside the range it crashes on, run from the arguments the lowering writes and read
 * back against a lossless copy of the same frames. A planned export picks hevc_videotoolbox where there is one, so
 * these name libx265 themselves to run on every machine that has it. A build without libx265 is held to an HEVC plan
 * that never reaches it instead, through another HEVC encoder or the codec fallback the planner names.
 */
class FfmpegX265NarrowFrameMediaTest {
  private val runtime = FfmpegRuntime.unshared(FfmpegConfig())
  private val fixture = FfmpegExportFixture()

  @Test
  fun `libx265 writes an SDR frame at a width it crashes on`() =
    runTest(timeout = TIMEOUT) {
      assertWritten("color=c=0x$FLAT:s=${NARROW.width}x${NARROW.height}:r=30:d=1,format=yuv420p", hdrTransfer = null)
    }

  @Test
  fun `libx265 writes a kept HDR grade at a width it crashes on`() =
    runTest(timeout = TIMEOUT) {
      assertWritten(
        "color=c=0x$FLAT:s=${NARROW.width}x${NARROW.height}:r=30:d=1,format=yuv420p10le," +
          "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv",
        HdrTransfer.Pq,
      )
    }

  private suspend fun assertWritten(
    source: String,
    hdrTransfer: HdrTransfer?,
  ) {
    val toolchain = runtime.toolchain().shouldBeInstanceOf<ToolchainResult.Available>().toolchain
    if (!toolchain.hasEncoder("libx265")) return assertPlannedWithoutLibx265()

    val encoded = File.createTempFile("filmstrip-x265-narrow", ".mp4")
    val lossless = File.createTempFile("filmstrip-x265-narrow", ".mkv")
    try {
      val encode =
        Invocation(
          inputs = listOf(InputSpec(InputSource.Generated(source))),
          filterGraph = "[0:v]null[v]",
          videoLabel = "v",
          audioLabel = null,
          output =
            OutputFormat(
              size = NARROW,
              videoCodec = VideoCodec.Hevc,
              audioCodec = AudioCodec.None,
              bitrate = COLOR_READ_BITRATE,
              frameRate = 30,
              audioFormat = null,
            ),
          videoEncoder = "libx265",
          duration = 1.seconds,
          hdrTransfer = hdrTransfer,
        ).arguments(toolchain, FfmpegConfig(), listOf(source), emptyList(), encoded.absolutePath)
      run(encode)
      val copy = listOf(toolchain.ffmpeg, "-y", "-loglevel", "error", "-f", "lavfi", "-i", source, "-c:v", "ffv1")
      run(copy + lossless.absolutePath)

      val centre = NARROW.width / 2 to NARROW.height / 2
      val read = tenBitCodesAt(encoded, centre)
      val expected = tenBitCodesAt(lossless, centre)
      withClue("read ${read.toList()}, expected ${expected.toList()}") {
        read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE }.shouldBeTrue()
      }
    } finally {
      encoded.delete()
      lossless.delete()
    }
  }

  private suspend fun assertPlannedWithoutLibx265() {
    withClue("the fixtures are absent") { fixture.available().shouldBeTrue() }
    val source = MediaSource.of(fixture.landscape.absolutePath)
    // Half of the fixture's 640x360, so nothing rounds and the only adjustment is the fallback, if any.
    val spec = ExportSpec(heightPx = 180, videoCodec = VideoCodec.Hevc)
    val capabilities =
      fixture.filmstrip
        .capabilities()
        .shouldBeInstanceOf<CapabilitiesResult.Success>()
        .capabilities

    val lowering = fixture.plannedLowering(compositionOf { clip(source) }, spec, listOf(source))

    if (capabilities.encoderFor(VideoCodec.Hevc) != null) {
      lowering.verdict
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.output.videoCodec shouldBe VideoCodec.Hevc
    } else {
      val degraded = lowering.verdict.shouldBeInstanceOf<Verdict.Degraded>()
      val fallback = degraded.adjustments.single { it.kind == AdjustmentKind.CodecFallback }
      fallback.requested shouldBe VideoCodec.Hevc.name
      fallback.resolved shouldBe degraded.plan.output.videoCodec.name
    }
    lowering.graph
      .shouldNotBeNull()
      .videoEncoder shouldNotBe "libx265"
  }

  private suspend fun run(command: List<String>) {
    val errors = StringBuilder()
    val exit = runtime.stream(command, onStdout = {}, onStderr = { errors.appendLine(it) })
    withClue("ffmpeg exited $exit: $errors") { exit shouldBe 0 }
  }

  private companion object {
    // The middle of the widths libx265 4.1 crashes on, tall enough for it to open at all.
    val NARROW = Size(42, 42)
    const val FLAT = "30C030"
  }
}
