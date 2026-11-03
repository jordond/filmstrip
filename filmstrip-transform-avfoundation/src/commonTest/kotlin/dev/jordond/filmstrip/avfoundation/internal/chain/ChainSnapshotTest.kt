package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a chain's snapshot hands composition-level effects to measure against, and the transfer it says reaches the
 * encoder.
 */
class ChainSnapshotTest {
  @Test
  fun `the transfer only reaches the encoder when the grade is kept`() {
    snapshot(encodesHdr = true).encodedTransfer shouldBe HdrTransfer.Pq
    snapshot(encodesHdr = false).encodedTransfer shouldBe null
    snapshot(encodesHdr = false).transfer shouldBe HdrTransfer.Pq
  }

  @Test
  fun `the composition span runs from zero to the composition's end`() {
    snapshot(encodesHdr = false).compositionSpan shouldBe TimeRange.of(Duration.ZERO, DURATION)
  }

  @Test
  fun `geometry measures the frame every clip and track effect left`() {
    val attributes = snapshot(encodesHdr = false).geometryAttributes

    attributes.inputSize shouldBe INPUT
    attributes.outputSize shouldBe OUTPUT
    attributes.layoutSize shouldBe INPUT
    attributes.span shouldBe TimeRange.of(Duration.ZERO, DURATION)
  }

  @Test
  fun `an effect after geometry measures the output frame on both sides`() {
    val attributes = snapshot(encodesHdr = false).compositionAttributes

    attributes.inputSize shouldBe OUTPUT
    attributes.outputSize shouldBe OUTPUT
    attributes.layoutSize shouldBe LAYOUT
    attributes.span shouldBe TimeRange.of(Duration.ZERO, DURATION)
  }

  @Test
  fun `both attributes follow the grade reaching the encoder`() {
    val kept = snapshot(encodesHdr = true)
    val mapped = snapshot(encodesHdr = false)

    listOf(kept.geometryAttributes, kept.compositionAttributes).forEach {
      it.colorSpace shouldBe ColorSpace.Bt2020
      it.hdrTransfer shouldBe HdrTransfer.Pq
    }
    listOf(mapped.geometryAttributes, mapped.compositionAttributes).forEach {
      it.colorSpace shouldBe ColorSpace.Bt709
      it.hdrTransfer shouldBe null
    }
  }

  @Test
  fun `the frame rate is the output's own`() {
    snapshot(encodesHdr = false, frameRate = 24).geometryAttributes.frameRate shouldBe 24f
    snapshot(encodesHdr = false, frameRate = 24).compositionAttributes.frameRate shouldBe 24f
  }

  private fun snapshot(
    encodesHdr: Boolean,
    frameRate: Int = 30,
  ): ChainSnapshot = ChainSnapshot(resolved(frameRate), spans = emptyList(), encodesHdr = encodesHdr)

  private fun resolved(frameRate: Int): ResolvedComposition =
    ResolvedComposition(
      tracks = emptyList(),
      compositionGeometry = emptyList(),
      compositionInputSize = INPUT,
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = OUTPUT,
          videoCodec = VideoCodec.Hevc,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = frameRate,
          audioFormat = null,
        ),
      layoutSize = LAYOUT,
      fit = Fit.Contain,
      fill = Fill.Black,
      duration = DURATION,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = HdrTransfer.Pq,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

  private companion object {
    val INPUT = Size(800, 600)
    val OUTPUT = Size(640, 360)
    val LAYOUT = Size(1280, 720)
    val DURATION = 3.seconds
  }
}
