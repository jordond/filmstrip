package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import kotlin.math.abs

/**
 * How far a texel's straight colour may read back from the sample it was authored at, in sixteen-bit samples, when the
 * texel is stored premultiplied in sixteen-bit samples with [alpha] as an eight-bit code.
 *
 * The store rounds the premultiplied colour to half a sample, and dividing by the alpha to read it back straight widens
 * that to half a sample over the alpha. A read that lands on whole sixteen-bit samples adds another half. Both fit
 * inside one sample over the alpha, which is what this gives, so a texel at alpha 0xFF reads back within one sample and
 * one at 0x40 within just under four.
 *
 * A store that premultiplies on the eight-bit grid misses by 128.5 samples at colour 0x80 and alpha 0x80, and by
 * 200.8 at colour 0xC8 and alpha 0x40, outside this bound at both.
 *
 * @throws IllegalArgumentException When [alpha] is not between 1 and 255, since a texel at alpha 0 has no colour.
 */
@InternalFilmstripApi
public fun straightSampleTolerance(alpha: Int): Float {
  require(alpha in 1..MAX_CODE) { "alpha $alpha is not a visible eight-bit code" }
  return MAX_CODE.toFloat() / alpha
}

/**
 * How far a translucent grey texel's straight HDR signal may read back from the signal its [straightCode] is authored
 * at, when the texel is stored premultiplied in sixteen-bit samples with [alpha] as an eight-bit code and drawn on a
 * [transfer] frame.
 *
 * The authored signal is [signalFromNits] of [authoredHdrNits] at [sampleOfCode] of [straightCode]. The stored sample
 * may land [straightSampleTolerance] either side of that, clamped to the sixteen-bit range, and this is the larger of
 * the two moves those ends make in signal, over all three channels. That sample bound is about twice what the store
 * rounds away, and the signal bound keeps the same slack on purpose. The read is taken to add nothing, so a test reads
 * the signal through a float working format, not Core Image's default half floats.
 *
 * @throws IllegalArgumentException When [alpha] is not between 1 and 255 or [straightCode] is not an eight-bit code.
 */
@InternalFilmstripApi
public fun straightSignalTolerance(
  alpha: Int,
  straightCode: Int,
  transfer: HdrTransfer,
): Float {
  val straight = sampleOfCode(straightCode)
  val tolerance = straightSampleTolerance(alpha)
  val authored = greySignal(straight.toFloat(), transfer)
  val low = greySignal(maxOf(straight - tolerance, 0f), transfer)
  val high = greySignal(minOf(straight + tolerance, MAX_SAMPLE.toFloat()), transfer)
  return authored.indices.maxOf { maxOf(abs(authored[it] - low[it]), abs(high[it] - authored[it])) }
}

/**
 * The [transfer] signal a grey whose every channel is the sixteen-bit [sample] is drawn at, red, green and blue.
 */
@InternalFilmstripApi
public fun greySignal(
  sample: Float,
  transfer: HdrTransfer,
): FloatArray {
  val level = sample / MAX_SAMPLE
  return transfer.signalFromNits(authoredHdrNits(level, level, level))
}

/**
 * The sixteen-bit sample an eight-bit [code] widens to, the code times 257, so code 0xFF lands on 65535.
 *
 * @throws IllegalArgumentException When [code] is not between 0 and 255.
 */
@InternalFilmstripApi
public fun sampleOfCode(code: Int): Int {
  require(code in 0..MAX_CODE) { "$code is not an eight-bit code" }
  return code * EIGHT_BIT_STEP
}

private const val MAX_CODE = 255
private const val MAX_SAMPLE = 65535
private const val EIGHT_BIT_STEP = 257
