package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import kotlin.time.Duration

/**
 * Everything one frame of a [CoreImageChain] is drawn from, taken together.
 *
 * A parameter edit produces a new composition and a new set of spans, and the two only describe the
 * same frame as each other. Held as one immutable object so a render reads a consistent pair rather
 * than one edit's spans against another edit's composition.
 *
 * @property resolved The composition the frame is drawn from.
 * @property spans Where each clip sits on the timeline, and what it draws.
 * @property encodesHdr Whether an HDR grade reaches the encoder.
 */
internal class ChainSnapshot(
  val resolved: ResolvedComposition,
  val spans: List<ClipSpan>,
  val encodesHdr: Boolean,
) {
  val output: OutputFormat = resolved.output

  /**
   * The run a composition-level effect is drawn over, which is the whole composition.
   */
  val compositionSpan: TimeRange = TimeRange.of(Duration.ZERO, resolved.duration)

  val transfer: HdrTransfer? = resolved.hdrTransfer

  /**
   * The transfer function reaching the encoder, or null when the export writes SDR.
   *
   * A tone-mapped export is SDR by the time a fill is drawn, however the sources were graded.
   */
  val encodedTransfer: HdrTransfer? = transfer.takeIf { encodesHdr }

  /**
   * What composition-level geometry measures against.
   *
   * The frame after every clip and track effect, since geometry is what pins it to [output]'s size
   * rather than something that already runs inside it.
   */
  val geometryAttributes: Attributes =
    Attributes(
      inputSize = resolved.compositionInputSize,
      outputSize = output.size,
      layoutSize = resolved.compositionInputSize,
      colorSpace = if (encodesHdr) ColorSpace.Bt2020 else ColorSpace.Bt709,
      hdrTransfer = encodedTransfer,
      frameRate = output.frameRate?.toFloat(),
      span = compositionSpan,
    )

  /**
   * What a composition-level effect that runs after geometry measures against. The output frame on
   * either side, since by the time one runs the frame has been pinned to it.
   */
  val compositionAttributes: Attributes =
    Attributes(
      inputSize = output.size,
      outputSize = output.size,
      layoutSize = resolved.layoutSize,
      colorSpace = if (encodesHdr) ColorSpace.Bt2020 else ColorSpace.Bt709,
      hdrTransfer = encodedTransfer,
      frameRate = output.frameRate?.toFloat(),
      span = compositionSpan,
    )
}
