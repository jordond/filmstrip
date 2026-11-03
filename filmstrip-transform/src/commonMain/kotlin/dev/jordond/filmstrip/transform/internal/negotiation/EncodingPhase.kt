package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.MAX_STILL_FRAME
import dev.jordond.filmstrip.geometry.Size

/**
 * The codec, the encoder, and the frame and rate it is held to.
 *
 * A frame above the encoder's ceiling is clamped and reported, not refused, since
 * AdjustmentKind.ResolutionClamped is what the model calls exactly this. A composition framed by a
 * still is held to MAX_STILL_FRAME on top of that, because a sensor's bounds are not a frame any
 * encoder has agreed to, and a spec that names its own height has already said what the frame
 * should be. `strict` is how a caller says the number was not negotiable, and that turns the same
 * clamp into a refusal. A copy runs no encoder, so there is no ceiling for the frame or the rate to
 * clamp to and the frame goes across whole.
 */
internal fun encodingOf(
  ask: Ask,
  framing: Framing,
  grade: Grade,
  ladder: List<VideoCodec>,
  hdrLadder: List<VideoCodec>,
  renderCapabilities: (Size, Boolean) -> RenderCapabilities,
): Phase<Encoding> {
  val spec = ask.spec
  val video =
    resolveVideoCodec(spec, ask.device, grade.encodesHdr, ladder, hdrLadder)
      ?: return Phase.Refused(FilmstripError.NoEncoder(spec.videoCodec, NO_ENCODER))
  val encoder = ask.device.encoderFor(video.codec)
  val copies = grade.path == ExportPath.Copy
  val requestedSize = framing.requestedSize

  val ceiling =
    tightest(encoder?.maxSize, MAX_STILL_FRAME.takeIf { framing.framedByStill && spec.heightPx == null })
  val outputSize =
    if (copies) requestedSize else requestedSize.fittedTo(ceiling, encoder?.sizeAlignment ?: SIZE_ALIGNMENT)
  val overCeiling =
    !copies && ceiling != null && (requestedSize.width > ceiling.width || requestedSize.height > ceiling.height)
  val frameRate = resolveFrameRate(spec, framing.firstVideo.frameRate, encoder?.maxFrameRate.takeIf { !copies })

  return Phase.Next(
    Encoding(
      video = video,
      encoder = encoder,
      ceiling = ceiling,
      overCeiling = overCeiling,
      outputSize = outputSize,
      layoutFrame = ask.layoutSize ?: outputSize,
      // A copy runs no effect chain and a tone-mapped export is SDR by the time one
      // runs, so the transfer an effect is lowered against is the one the encoder is handed.
      target =
        LoweringTarget(
          outputSize = outputSize,
          capabilities = renderCapabilities(outputSize, grade.encodesHdr),
          frameRate = frameRate,
          hdrTransfer = grade.hdrTransfer.takeIf { grade.encodesHdr },
        ),
    ),
  )
}

private const val SIZE_ALIGNMENT = 2

private const val NO_ENCODER = "This device has no encoder for the requested codec."
