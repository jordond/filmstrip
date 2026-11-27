package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.MeasuredFrame
import dev.jordond.filmstrip.transform.internal.negotiation.fittedBetween
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Exports asked for under and above the smallest frame the browser says each encoder supports, read back at the size
 * the planner settled on.
 */
class BrowserMinFrameMediaTest {
  @Test
  fun anExportUnderTheFloorIsWrittenAtTheSizeThePlannerLiftsItTo() =
    runTest {
      encoders().forEach { encoder -> assertWritten(encoder, (encoder.minSize.height / 2).coerceAtLeast(1)) }
    }

  @Test
  fun anExportAtTheFloorIsWrittenThere() =
    runTest {
      encoders().forEach { encoder -> assertWritten(encoder, maxOf(encoder.minSize.width, encoder.minSize.height)) }
    }

  @Test
  fun anExportBetweenTheFloorAndTheSourceIsLeftAlone() =
    runTest {
      encoders().forEach { encoder ->
        val height = (maxOf(encoder.minSize.width, encoder.minSize.height) + SOURCE_SIDE) / 2

        val written = assertWritten(encoder, height)

        withClue("a frame above the floor was lifted") {
          written.height shouldBe height - height % encoder.sizeAlignment
        }
      }
    }

  // The source is square, so a frame asked for at a height is that height on both sides.
  private suspend fun assertWritten(
    encoder: VideoEncoderCapability,
    height: Int,
  ): Size {
    val asked = Size(height, height)
    val expected =
      MeasuredFrame(
        asked,
      ).fittedBetween(encoder.minSize, encoder.maxSize, encoder.sizeAlignment).shouldNotBeNull()
    val source = MediaSource.Bytes(makeClip(width = SOURCE_SIDE, height = SOURCE_SIDE))
    val composition = Composition(listOf(Track(listOf(Clip(source)))))
    val spec = ExportSpec(heightPx = height, videoCodec = encoder.codec, audioCodec = AudioCodec.None)

    withClue("${encoder.codec} asked for $asked on a floor of ${encoder.minSize}") {
      val plan = planOf(webCodecsFilmstrip(), composition, spec)
      plan.output.videoCodec shouldBe encoder.codec
      plan.output.size shouldBe expected
      val written = exportOf(composition, MediaSink.Uri(""), spec)
      written.info.video
        .shouldNotBeNull()
        .displaySize shouldBe expected
    }
    return expected
  }

  private suspend fun encoders(): List<VideoEncoderCapability> =
    webCodecsFilmstrip()
      .capabilities()
      .shouldBeInstanceOf<CapabilitiesResult.Success>()
      .capabilities.video
      .distinctBy { it.codec }
      .shouldNotBeEmpty()
      .also { all -> all.forEach { println("MinFrame ${it.codec} floor ${it.minSize} alignment ${it.sizeAlignment}") } }

  private companion object {
    const val SOURCE_SIDE = 256
  }
}
