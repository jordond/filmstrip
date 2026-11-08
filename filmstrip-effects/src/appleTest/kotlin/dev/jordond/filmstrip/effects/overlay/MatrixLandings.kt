package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.IDENTITY_NOT_444_REASON
import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.nearestReadable
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.CicpTag
import kotlin.time.Duration.Companion.seconds

// What the matrix landing tests share: how an overlay on a still resolves, what it reports when refused before
// decoding and where its stored samples are read back.

/**
 * How an image overlay on the picture in [bytes] resolves on a 64 by 64 SDR frame.
 */
internal fun overlayResolution(bytes: ByteArray): EffectResolution {
  val overlay = ImageOverlay(image = ImageSource.ofBytes(bytes), corner = Corner.TopStart)
  return checkNotNull(BuiltInEffectResolver().resolve(overlay, CAPABILITIES, ATTRIBUTES))
}

/**
 * What the Apple raster reports for a still with [nclx] and [bitDepth]-bit samples that
 * [AppleStillSamples.decoderMatrix] refuses before decoding, or null when it lands. A reading core refuses reports
 * core's reason, the identity matrix on samples that are not 4:4:4 reports core's reason for that, and a landing past
 * the bound reports [pastBound] of core's nearest readable matrix.
 */
internal fun refusalOf(
  nclx: CicpTag,
  unsubsampled: Boolean,
  bitDepth: Int,
): String? {
  if (AppleStillSamples.decoderMatrix(nclx, unsubsampled, bitDepth) != null) return null
  val reading = ycbcrReading(nclx.matrix, nclx.primaries, nclx.transfer)
  if (reading is YcbcrReading.Refused) return reading.reason
  if (nclx.matrix == IDENTITY && !unsubsampled) return IDENTITY_NOT_444_REASON
  return pastBound(checkNotNull(reading.nearestReadable(AppleStillSamples.READABLE_MATRICES - IDENTITY)))
}

/**
 * The sixteen-bit red, green, blue and alpha this picture's stored samples hold at its centre, or null when the raster
 * reads none back here.
 */
internal fun OverlayPixels.centreSamples(): ShortArray? {
  val samples = stored ?: return null
  val centre = (size.height / 2 * size.width + size.width / 2) * CHANNELS
  return samples.copyOfRange(centre, centre + CHANNELS)
}

/**
 * How [resolution] reads in a test's failure line.
 */
internal fun describe(resolution: EffectResolution): String =
  when (resolution) {
    is EffectResolution.Unsupported -> "unsupported: ${resolution.message}"
    is EffectResolution.Failed -> "failed: ${resolution.error.message}"
    is EffectResolution.Degraded -> "degraded: ${resolution.causes.map { it.message }}"
    is EffectResolution.Resolved -> "resolved"
  }

private const val IDENTITY = 0
private const val CHANNELS = 4
private val FRAME = Size(64, 64)

private val ATTRIBUTES =
  Attributes(
    inputSize = FRAME,
    outputSize = FRAME,
    layoutSize = FRAME,
    colorSpace = ColorSpace.Bt709,
    hdrTransfer = null,
    frameRate = 30f,
    span = TimeRange.of(0.seconds, 1.seconds),
  )

private val CAPABILITIES =
  RenderCapabilities(
    api = RenderApi.Metal,
    supportsFragmentShader = true,
    supportsComputeShader = true,
    supportsHdr = false,
    colorSpaces = setOf(ColorSpace.Bt709),
    maxTextureSize = 8_192,
    features = emptySet(),
  )
