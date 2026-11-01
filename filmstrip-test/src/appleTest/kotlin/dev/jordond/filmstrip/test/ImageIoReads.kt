package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.test.AppleStillSamples.EIGHT_BIT_STEP
import dev.jordond.filmstrip.test.AppleStillSamples.SAMPLE_MASK
import platform.Foundation.NSProcessInfo
import kotlin.math.abs

/**
 * Why the eight-bit centre ImageIO decodes [bytes] to sits further than [AppleStillSamples.TOLERANCE] from every one of
 * the [decodes] given for it, or null when it lands on one. A decode unmeasured here is the miss instead, so a test
 * reading many stills reports each.
 *
 * @param decodes The decodes Apple's decoder may hand back, given the texel ImageIO handed back here.
 */
@OptIn(InternalFilmstripApi::class)
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

private const val RGB = 3
