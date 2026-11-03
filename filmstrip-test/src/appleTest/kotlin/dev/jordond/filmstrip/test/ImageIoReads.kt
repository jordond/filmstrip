package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.picture.heif.RestatedColor
import dev.jordond.filmstrip.picture.heif.RestatedHeif
import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import dev.jordond.filmstrip.picture.heif.heifWithRestatedColor
import dev.jordond.filmstrip.test.AppleStillSamples.EIGHT_BIT_STEP
import dev.jordond.filmstrip.test.AppleStillSamples.SAMPLE_MASK
import io.kotest.assertions.withClue
import io.kotest.matchers.types.shouldBeInstanceOf
import platform.Foundation.NSProcessInfo
import kotlin.math.abs

/**
 * Why the eight-bit centre ImageIO decodes [bytes] to sits further than [AppleStillSamples.TOLERANCE] from every one of
 * the [decodes] given for it, or null when it lands on one. A decode unmeasured here is the miss instead, so a test
 * reading many stills reports each.
 *
 * @param decodes The decodes Apple's decoder may hand back, given the texel ImageIO handed back here.
 */
internal fun imageIoMiss(
  name: String,
  bytes: ByteArray,
  decodes: (ShortArray) -> List<ShortArray>,
): String? =
  AppleStillSamples.missOrUnmeasured {
    val read = imageIoCentre(bytes)
    val models =
      decodes(ShortArray(RGB) { (read[it] * EIGHT_BIT_STEP).toShort() }).map { model ->
        model.take(RGB).map { (it.toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP.toFloat() }
      }
    val near = models.any { model -> read.indices.all { abs(read[it] - model[it]) <= AppleStillSamples.TOLERANCE } }
    if (near) null else "$name decoded to $read through ImageIO, where its models are $models"
  }

internal fun osBuild(): String =
  "${NSProcessInfo.processInfo.operatingSystemVersionString}${if (isSimulator()) " simulator" else ""}"

/**
 * [bytes] with their codestream and `nclx` stating [matrix], the way the overlay raster hands a still under [nclx] to
 * ImageIO. A still already under [matrix] goes through [heifWithExplicitCodestreamColor] alone.
 */
internal fun landedBytes(
  bytes: ByteArray,
  nclx: CicpTag,
  matrix: Int,
): ByteArray {
  if (matrix == nclx.matrix) return heifWithExplicitCodestreamColor(bytes).handed
  val restated = heifWithRestatedColor(bytes) { _, _, _ -> RestatedColor(matrix, nclx.primaries, nclx = true) }
  return withClue("the still with nclx $nclx was not restated to $matrix") {
    restated.shouldBeInstanceOf<RestatedHeif.Restated>()
  }.bytes
}

private const val RGB = 3
