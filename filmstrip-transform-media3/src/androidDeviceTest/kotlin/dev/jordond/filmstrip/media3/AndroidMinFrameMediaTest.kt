package dev.jordond.filmstrip.media3

import android.util.Log
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.transform.internal.negotiation.fittedBetween
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Exports asked for under and above the smallest frame each of this device's encoders publishes, read back at the
 * size the planner settled on. Each codec is read through the encoder the planner picks for it.
 */
class AndroidMinFrameMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun anExportUnderTheFloorIsWrittenAtTheSizeThePlannerLiftsItTo() =
    runTest(timeout = TIMEOUT) {
      encoders().forEach { encoder -> assertWritten(encoder, (encoder.minSize.height / 2).coerceAtLeast(1)) }
    }

  @Test
  fun anExportAtTheFloorIsWrittenThere() =
    runTest(timeout = TIMEOUT) {
      encoders().forEach { encoder -> assertWritten(encoder, heightReaching(encoder)) }
    }

  @Test
  fun anExportBetweenTheFloorAndTheSourceIsLeftAlone() =
    runTest(timeout = TIMEOUT) {
      val source = shape()
      encoders().forEach { encoder ->
        val height = (heightReaching(encoder) + source.height) / 2

        val written = assertWritten(encoder, height)

        withClue("a frame above the floor was lifted") {
          written.height shouldBe height - height % encoder.sizeAlignment
        }
      }
    }

  private suspend fun assertWritten(
    encoder: VideoEncoderCapability,
    height: Int,
  ): Size {
    val source = shape()
    val asked = Size((height * source.width.toFloat() / source.height).roundToInt().coerceAtLeast(1), height)
    val expected = asked.fittedBetween(encoder.minSize, encoder.maxSize, encoder.sizeAlignment).shouldNotBeNull()
    val composition = Composition(listOf(Track(listOf(Clip(probe.fixture())))))

    val plan = probe.plan(composition, ExportSpec(heightPx = height, videoCodec = encoder.codec))
    withClue("${encoder.codec} asked for $asked on a floor of ${encoder.minSize}") {
      plan.output.videoCodec shouldBe encoder.codec
      plan.output.size shouldBe expected
      probe
        .finish(plan)
        .info.video
        .shouldNotBeNull()
        .displaySize shouldBe expected
    }
    return expected
  }

  // The smallest height whose frame, at the fixture's shape, has both sides on the floor.
  private suspend fun heightReaching(encoder: VideoEncoderCapability): Int {
    val source = shape()
    val forWidth = (encoder.minSize.width * source.height.toFloat() / source.width).roundToInt()
    return maxOf(encoder.minSize.height, forWidth)
  }

  private suspend fun shape(): Size =
    probe
      .infoOf(probe.fixture())
      .video
      .shouldNotBeNull()
      .displaySize

  private suspend fun encoders(): List<VideoEncoderCapability> {
    val capabilities =
      probe.filmstrip
        .capabilities()
        .shouldBeInstanceOf<CapabilitiesResult.Success>()
        .capabilities
    capabilities.video.forEach { Log.i(TAG, "${it.codec} floor ${it.minSize} alignment ${it.sizeAlignment}") }
    return capabilities.video.distinctBy { it.codec }.shouldNotBeEmpty()
  }

  private companion object {
    const val TAG = "MinFrame"
  }
}
