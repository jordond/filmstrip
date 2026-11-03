package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.test.AppleBufferCodes
import kotlin.math.roundToInt

/**
 * This color as [FrameProbe] reads it, carried into the codes an SDR export's BT.709 buffer holds, each channel a
 * fraction of one.
 *
 * A probe draws the decoded frame into device RGB, so what it reads has been color matched out of the codes the file
 * was written with. Those codes are what an SDR effect works on, so a test predicting where one lands moves the
 * probe's reading into them first and [inProbeCodes] carries the prediction back.
 */
internal fun Triple<Int, Int, Int>.inBufferCodes(): FloatArray =
  AppleBufferCodes.fromDevice(floatArrayOf(first / FULL, second / FULL, third / FULL))

/**
 * These BT.709 buffer codes as [FrameProbe] would read them, in `0..255`.
 */
internal fun FloatArray.inProbeCodes(): Triple<Int, Int, Int> {
  val probe = AppleBufferCodes.toDevice(this)
  return Triple((probe[0] * FULL).roundToInt(), (probe[1] * FULL).roundToInt(), (probe[2] * FULL).roundToInt())
}

private const val FULL = 255f

/**
 * This color's codes taken as the buffer's own, each channel a fraction of one.
 *
 * An authored color lands in an SDR buffer as its own codes, with no color management on the way, so this is
 * where a fill, a text plate or an overlay image's pixels are predicted to land.
 */
internal fun Triple<Int, Int, Int>.asBufferCodes(): FloatArray = floatArrayOf(first / FULL, second / FULL, third / FULL)
