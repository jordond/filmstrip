@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.TestScratch
import dev.jordond.filmstrip.transform.internal.negotiation.fittedBetween
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Exports asked for under and above the smallest frame each VideoToolbox encoder opens a session at, read back at the
 * size the planner settled on.
 */
class AppleMinFrameHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `an export under the floor is written at the size the planner lifts it to`() =
    runTest(timeout = TIMEOUT) {
      encoders().forEach { encoder -> assertWritten(encoder, (encoder.minSize.height / 2).coerceAtLeast(1)) }
    }

  @Test
  fun `an export at the floor is written there`() =
    runTest(timeout = TIMEOUT) {
      encoders().forEach { encoder -> assertWritten(encoder, heightReaching(encoder)) }
    }

  @Test
  fun `an export between the floor and the source is left alone`() =
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
    val composition = compositionOf { clip(MediaSource.of(fixture(CLIP))) }
    val spec = ExportSpec(heightPx = height, videoCodec = encoder.codec)
    val output = TestScratch.directory + "filmstrip-apple-min-frame-${encoder.codec}-$height.mp4"

    try {
      withClue("${encoder.codec} asked for $asked on a floor of ${encoder.minSize}") {
        val plan =
          when (val verdict = withContext(Dispatchers.Default) { filmstrip.plan(composition, spec) }) {
            is Verdict.Capable -> verdict.plan
            is Verdict.Degraded -> verdict.plan
            is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
          }
        plan.output.videoCodec shouldBe encoder.codec
        plan.output.size shouldBe expected
        val written = filmstrip.exportOrFail(composition, spec, output)
        written.info.video
          .shouldNotBeNull()
          .displaySize shouldBe expected
      }
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(output, null)
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
    withContext(Dispatchers.Default) { filmstrip.probe(MediaSource.of(fixture(CLIP))) }
      .shouldBeInstanceOf<ProbeResult.Success>()
      .info.video
      .shouldNotBeNull()
      .displaySize

  private suspend fun encoders(): List<VideoEncoderCapability> =
    withContext(Dispatchers.Default) { filmstrip.capabilities() }
      .shouldBeInstanceOf<CapabilitiesResult.Success>()
      .capabilities.video
      .distinctBy { it.codec }
      .shouldNotBeEmpty()
      .also { all -> all.forEach { println("MinFrame ${it.codec} floor ${it.minSize} alignment ${it.sizeAlignment}") } }

  private companion object {
    val TIMEOUT = 2.seconds * 60
    const val CLIP = "apple_export_long.mp4"
  }
}
