package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.FLOOR_LADDER
import dev.jordond.filmstrip.transform.internal.negotiation.fittedBetween
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * The smallest frame each real encoder of this build reports, held against the encoder itself, and exports asked for
 * under it read back at the size the planner lifted them to.
 */
class FfmpegMinFrameMediaTest {
  private val runtime = FfmpegRuntime.unshared(FfmpegConfig())
  private val fixture = FfmpegExportFixture()

  @Test
  fun `every encoder opens at the floor it reports and at no rung under it`() =
    runTest(timeout = TIMEOUT) {
      val encoders = encoders()
      encoders.shouldNotBeEmpty()

      encoders.forEach { encoder ->
        val name = encoder.encoderName.shouldNotBeNull()
        withClue("$name at its floor ${encoder.minSize}") { encodes(name, encoder.minSize) shouldBe 0 }
        FLOOR_LADDER.takeWhile { it != encoder.minSize }.forEach { rung ->
          withClue("$name under its floor at $rung") { encodes(name, rung) shouldNotBe 0 }
        }
      }
    }

  @Test
  fun `an export asked for under the floor is written at the size the planner lifts it to`() =
    runTest(timeout = TIMEOUT) {
      withClue("the fixtures are absent") { fixture.available().shouldBeTrue() }
      val source = MediaSource.of(fixture.landscape.absolutePath)
      val frame = fixture.filmstrip.probe(source).shouldBeInstanceOf<ProbeResult.Success>()
      val shape =
        frame.info.video
          .shouldNotBeNull()
          .displaySize

      encoders().distinctBy { it.codec }.forEach { encoder ->
        listOf(SMALL_HEIGHT, MIDDLE_HEIGHT).forEach { height ->
          val asked = Size((height * shape.width.toFloat() / shape.height).roundToInt(), height)
          val expected = asked.fittedBetween(encoder.minSize, encoder.maxSize, encoder.sizeAlignment).shouldNotBeNull()
          val spec = ExportSpec(heightPx = height, videoCodec = encoder.codec)
          val output = File.createTempFile("filmstrip-min-frame", ".mp4").also { it.delete() }
          try {
            val plan = fixture.plannedRun(compositionOf { clip(source) }, spec)
            withClue("${encoder.encoderName} asked for $asked") {
              plan.output.size shouldBe expected
              fixture
                .exported(plan, output)
                .video
                .shouldNotBeNull()
                .displaySize shouldBe expected
            }
          } finally {
            output.delete()
          }
        }
      }
    }

  private suspend fun encoders(): List<VideoEncoderCapability> =
    fixture.filmstrip
      .capabilities()
      .shouldBeInstanceOf<CapabilitiesResult.Success>()
      .capabilities.video

  private suspend fun encodes(
    encoder: String,
    size: Size,
  ): Int {
    val toolchain = runtime.toolchain().shouldBeInstanceOf<ToolchainResult.Available>().toolchain
    val source = "color=s=${size.width}x${size.height}:r=30:d=0.2,format=yuv420p"
    val command =
      listOf(toolchain.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", source) +
        listOf("-c:v", encoder, "-f", "null", "-")
    return runtime.stream(command, onStdout = {}, onStderr = {})
  }

  private companion object {
    // Under every floor measured so far but libx264's, so an encoder with a floor has to lift it.
    const val SMALL_HEIGHT = 8

    // Between every measured floor and the fixture's own height, where nothing is lifted.
    const val MIDDLE_HEIGHT = 180
  }
}
