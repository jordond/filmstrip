package dev.jordond.filmstrip.iosharness

import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference

/**
 * Where the overlay sits, in normalised `[-1, 1]` coordinates, plus when it was last written.
 */
data class OverlayPlacement(
  val generation: Long,
  val x: Float,
  val y: Float,
)

/**
 * The iOS side of the same live-parameter contract the Android harness uses.
 *
 * A parameter write mutates this and nothing else, and the chain is not rebuilt. Unlike Android,
 * there is no per-pipeline wrapper needed here. Core Image filters are value-like descriptions rather
 * than objects holding GL texture names, so the same placement can feed the transport composition
 * and the still renderer without the texture-identity problem that forced the split on Android.
 */
class OverlayParams {
  private val current = AtomicReference(OverlayPlacement(0, 0f, 0f))
  private val counter = AtomicLong(0)
  private val writeTimesNs = LongArray(WRITE_HISTORY)

  val placement: OverlayPlacement get() = current.value
  val generation: Long get() = counter.value

  fun setPosition(
    x: Float,
    y: Float,
    atNs: Long,
  ): Long {
    val next = counter.addAndGet(1)
    current.value = OverlayPlacement(next, x, y)
    writeTimesNs[(next % WRITE_HISTORY).toInt()] = atNs
    return next
  }

  fun writeTimeNs(generation: Long): Long = writeTimesNs[(generation % WRITE_HISTORY).toInt()]

  private companion object {
    const val WRITE_HISTORY = 2048
  }
}
