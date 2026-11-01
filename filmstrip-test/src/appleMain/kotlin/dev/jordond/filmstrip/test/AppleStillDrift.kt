package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.test.AppleStillSamples.EIGHT_BIT_STEP
import dev.jordond.filmstrip.test.AppleStillSamples.HeicDecode
import dev.jordond.filmstrip.test.AppleStillSamples.MAX_CODE
import dev.jordond.filmstrip.test.AppleStillSamples.SAMPLE_MASK
import dev.jordond.filmstrip.test.AppleStillSamples.WHOLE_CODE_DRIFT
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Every whole code within WHOLE_CODE_DRIFT of the unrounded reading on each channel, starting from the reading
// rounded, the texel ycbcrToRgb gives.
internal fun withinDrift(decode: HeicDecode): List<ShortArray> {
  val rounded = roundedReading(decode)
  val (reds, greens, blues) =
    AppleStillSamples.unroundedReading(decode).map { code ->
      val low = ceil(code - WHOLE_CODE_DRIFT).toInt().coerceAtLeast(0)
      val high = floor(code + WHOLE_CODE_DRIFT).toInt().coerceAtMost(MAX_CODE)
      (low..high).toList()
    }
  val box =
    reds.flatMap { red ->
      greens.flatMap { green -> blues.map { blue -> AppleStillSamples.wholeCodeTexel(intArrayOf(red, green, blue)) } }
    }
  return listOf(rounded) + box.filterNot { it.contentEquals(rounded) }
}

// The unrounded reading rounded to whole codes on each channel, inside 0 to 255 since core's evaluator clamps, as
// ycbcrToRgb gives it.
internal fun roundedReading(decode: HeicDecode): ShortArray {
  val codes = AppleStillSamples.unroundedReading(decode).map { it.roundToInt() }
  return AppleStillSamples.wholeCodeTexel(codes.toIntArray())
}

// The decodes read lands on within TOLERANCE on red, green and blue, or all of them when it lands on none, so a miss
// still names every decode.
internal fun List<ShortArray>.narrowedTo(read: ShortArray?): List<ShortArray> {
  if (read == null) return this
  val landed =
    filter { decode ->
      (0 until RGB).all { abs(eightBitCode(decode[it]) - eightBitCode(read[it])) <= AppleStillSamples.TOLERANCE }
    }
  return landed.ifEmpty { this }
}

private fun eightBitCode(sample: Short): Float = (sample.toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP.toFloat()

private const val RGB = 3
