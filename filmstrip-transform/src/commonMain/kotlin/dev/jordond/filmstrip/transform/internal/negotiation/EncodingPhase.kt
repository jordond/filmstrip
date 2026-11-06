package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.MAX_STILL_FRAME
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.videoCodecOf
import dev.jordond.filmstrip.transform.internal.encode.DEFAULT_QUALITY
import dev.jordond.filmstrip.transform.internal.encode.resolveVideoBitrate

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
 *
 * A transcode that writes video always settles on a bitrate, the one the spec names or else the one its quality tier
 * resolves to through [resolveVideoBitrate], with [DEFAULT_QUALITY] standing in for no tier. One above the encoder's
 * own ceiling is held to it and reported the same way the frame is. A copy and an audio-only export settle none.
 */
internal fun encodingOf(
  ask: Ask,
  framing: Framing,
  grade: Grade,
  streams: Streams,
  ladder: List<VideoCodec>,
  hdrLadder: List<VideoCodec>,
  renderCapabilities: (Size, Boolean) -> RenderCapabilities,
): Phase<Encoding> {
  val spec = ask.spec
  val sourceCodec = videoCodecOf(framing.firstVideo.codec.kind)
  // A negotiation handed its grade writes no file, so having no encoder for a kept grade refuses nothing.
  val video =
    resolveVideoCodec(spec, ask.device, grade.encodesHdr, ladder, hdrLadder, sourceCodec)
      ?: ask.grade?.let { resolveVideoCodec(spec, ask.device, encodesHdr = false, ladder, hdrLadder, sourceCodec) }
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
  val asked =
    if (copies || !streams.keepVideo) {
      null
    } else {
      spec.bitrate ?: resolveVideoBitrate(spec.quality ?: DEFAULT_QUALITY, video.codec, outputSize, frameRate)
    }
  val maxBitrate = encoder?.maxBitrate?.takeIf { asked != null && asked.bitsPerSecond > it.bitsPerSecond }

  return Phase.Next(
    Encoding(
      video = video,
      encoder = encoder,
      ceiling = ceiling,
      overCeiling = overCeiling,
      outputSize = outputSize,
      bitrate = maxBitrate ?: asked,
      bitrateClamp = asked?.let { requested -> maxBitrate?.let { bitrateClamp(requested, it) } },
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

private fun bitrateClamp(
  asked: Bitrate,
  ceiling: Bitrate,
): Concession {
  // Rates that round to the same label read in exact bits per second, or the clamp would report no change.
  val exact = asked.toString() == ceiling.toString()
  val requested = if (exact) "${asked.bitsPerSecond} bps" else asked.toString()
  val resolved = if (exact) "${ceiling.bitsPerSecond} bps" else ceiling.toString()
  val message = "The encoder takes at most $resolved, so the bitrate is held there."
  return Concession(
    adjustment =
      Adjustment(
        kind = AdjustmentKind.BitrateClamped,
        requested = requested,
        resolved = resolved,
        message = message,
      ),
    strictRefusal = FilmstripError.InvalidComposition(message),
  )
}

private const val SIZE_ALIGNMENT = 2

private const val NO_ENCODER = "This device has no encoder for the requested codec."
