package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr

/**
 * Everything an export gave up against what [spec] asked for, in the order a caller reads it.
 *
 * @param dropped The effect ids the negotiation left out because the device cannot run them.
 */
internal fun concessionsOf(
  spec: ExportSpec,
  framing: Framing,
  grade: Grade,
  encoding: Encoding,
  audio: CodecChoice<AudioCodec>,
  lowered: List<LoweredEffect>,
  dropped: Set<String>,
): List<Concession> =
  buildList {
    encoding.video.fallback?.let(::add)
    audio.fallback?.let(::add)
    resolutionClamp(framing, encoding)?.let(::add)
    val frameRate = encoding.target.frameRate
    val requestedRate = spec.frameRate
    if (requestedRate != null && requestedRate != frameRate) {
      add(
        concession(
          kind = AdjustmentKind.FrameRateClamped,
          requested = "$requestedRate fps",
          resolved = "$frameRate fps",
          message = "The encoder does not accept $requestedRate fps, so $frameRate is used instead.",
        ),
      )
    }
    // Auto is the only mode that lands somewhere the caller did not name, so it is the only one
    // with anything to report. ToneMapToSdr asked for exactly this, and honouring a request is not
    // an adjustment to it.
    if (grade.sourceIsHdr && grade.hdr == ResolvedHdr.ToneMap && spec.hdr == HdrMode.Auto) {
      add(
        concession(
          kind = AdjustmentKind.HdrToneMapped,
          requested = spec.hdr.name,
          resolved = HdrMode.ToneMapToSdr.name,
          // Auto reaches a tone map two ways, and a device that encodes HDR fine still gets here on
          // a mix, so the message follows whether the grades agree.
          message = if (grade.gradesAgree) TONE_MAPPED else TONE_MAPPED_MIXED,
        ),
      )
    }
    lowered.flatMap { it.degraded }.distinct().forEach { (id, message) ->
      add(concession(AdjustmentKind.EffectApproximated, id, "approximated", message))
    }
    dropped.forEach { id ->
      add(concession(AdjustmentKind.EffectDropped, id, "removed", "$id $DROPPED"))
    }
  }

/**
 * The frame the encoder moved away from the requested one, or null when it took it as asked.
 */
private fun resolutionClamp(
  framing: Framing,
  encoding: Encoding,
): Concession? {
  val requestedSize = framing.requestedSize
  val outputSize = encoding.outputSize
  if (requestedSize == outputSize) return null

  val message =
    if (encoding.overCeiling) {
      "This export encodes into ${checkNotNull(encoding.ceiling).describe()} at most, so the frame is scaled " +
        "down to fit inside it and keeps its shape."
    } else {
      "The encoder wants aligned dimensions, so each side rounds to what it accepts."
    }
  return Concession(
    Adjustment(AdjustmentKind.ResolutionClamped, requestedSize.describe(), outputSize.describe(), message),
    FilmstripError.UnsupportedResolution(requestedSize, encoding.ceiling ?: outputSize, message),
  )
}

private fun concession(
  kind: AdjustmentKind,
  requested: String,
  resolved: String,
  message: String,
): Concession = Concession(Adjustment(kind, requested, resolved, message), FilmstripError.InvalidComposition(message))

private fun Size.describe(): String = "${width}x$height"

private const val TONE_MAPPED = "This device cannot encode HDR, so the grade is tone-mapped to SDR."

private const val TONE_MAPPED_MIXED =
  "These sources do not share one HDR transfer, so no single grade describes the output and " +
    "they are tone-mapped to SDR together."

private const val DROPPED = "cannot run on this device and is not in this plan."
