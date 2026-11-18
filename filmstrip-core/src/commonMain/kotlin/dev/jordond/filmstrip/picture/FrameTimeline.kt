package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.media.FramePlays
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Says which frame of a sequence an output slot shows.
 *
 * Frame `k` of play `p` starts at the drawn start plus `p` cycles plus the sum of the durations before `k`, counted in
 * whole nanoseconds. A start lands on the output slot [outputSlotOf] names for it. A slot shows the last frame whose
 * start has landed on it or before it, so two frames that land on one slot show the later one. A slot before the first
 * start shows frame 0, and once the plays are spent the last frame holds.
 *
 * A timeline whose durations are all equal multiplies to find a start and never adds up, so a duration that does not
 * divide a second evenly does not drift.
 *
 * @property frameCount The number of frames in one play.
 * @property plays How many times the sequence plays.
 */
@InternalFilmstripApi
public class FrameTimeline private constructor(
  public val frameCount: Int,
  private val uniformNanos: Long,
  private val starts: LongArray?,
  private val cycleNanos: Long,
  public val plays: FramePlays,
) {
  /**
   * Builds a timeline from one duration per frame.
   *
   * @throws IllegalArgumentException when [durations] is empty or holds a duration that is not positive and finite.
   */
  public constructor(durations: List<Duration>, plays: FramePlays) : this(
    frameCount = durations.also { checkDurations(it) }.size,
    uniformNanos = if (durations.all { it == durations[0] }) durations[0].inWholeNanoseconds else 0L,
    starts = if (durations.all { it == durations[0] }) null else startsOf(durations),
    cycleNanos = cycleOf(durations),
    plays = plays,
  )

  /**
   * The time one pass through every frame takes.
   */
  public val cycle: Duration get() = cycleNanos.nanoseconds

  /**
   * The time every play takes, or null when the sequence plays forever.
   */
  public val playTime: Duration?
    get() =
      when (plays) {
        FramePlays.Forever -> null
        is FramePlays.Times -> cycle * plays.count
      }

  /**
   * The frame shown on [slot] at [frameRate], for a sequence whose first frame starts at [drawnStart].
   *
   * @throws IllegalArgumentException when [frameRate] is zero or negative.
   */
  public fun frameOnSlot(
    drawnStart: Duration,
    slot: Long,
    frameRate: Int,
  ): Int {
    val startNanos = drawnStart.inWholeNanoseconds

    fun landing(index: Long): Long = outputSlotOf(startOf(index, startNanos), frameRate)

    var low = 0L
    var high =
      when (plays) {
        is FramePlays.Times -> {
          plays.count.toLong() * frameCount - 1
        }
        FramePlays.Forever -> {
          var bound = frameCount.toLong()
          while (bound < Long.MAX_VALUE / 2 && landing(bound) <= slot) bound *= 2
          bound
        }
      }
    if (landing(low) > slot) return 0
    while (low < high) {
      val middle = low + (high - low + 1) / 2
      if (landing(middle) <= slot) low = middle else high = middle - 1
    }
    return (low % frameCount).toInt()
  }

  /**
   * The frame shown at [compositionTime], for a backend handed a timestamp rather than a slot.
   *
   * The time is put on its output slot first, so no backend floors it.
   *
   * @throws IllegalArgumentException when [frameRate] is zero or negative.
   */
  public fun frameAt(
    drawnStart: Duration,
    compositionTime: Duration,
    frameRate: Int,
  ): Int = frameOnSlot(drawnStart, outputSlotOf(compositionTime, frameRate), frameRate)

  private fun startOf(
    index: Long,
    drawnStartNanos: Long,
  ): Duration {
    val play = index / frameCount
    val frame = (index % frameCount).toInt()
    val inPlay = if (starts == null) uniformNanos * frame else starts[frame]
    return saturatingAdd(saturatingAdd(drawnStartNanos, saturatingTimes(cycleNanos, play)), inPlay).nanoseconds
  }

  @InternalFilmstripApi
  public companion object {
    /**
     * Builds a timeline of [frameCount] frames that each last [frameDuration].
     *
     * @throws IllegalArgumentException when [frameCount] is below one or [frameDuration] is not positive and finite.
     */
    public fun uniform(
      frameCount: Int,
      frameDuration: Duration,
      plays: FramePlays,
    ): FrameTimeline {
      require(frameCount >= 1) { "A timeline needs at least one frame, and $frameCount frames is not one." }
      checkDurations(listOf(frameDuration))
      return FrameTimeline(
        frameCount = frameCount,
        uniformNanos = frameDuration.inWholeNanoseconds,
        starts = null,
        cycleNanos = saturatingTimes(frameDuration.inWholeNanoseconds, frameCount.toLong()),
        plays = plays,
      )
    }
  }
}

private fun checkDurations(durations: List<Duration>) {
  require(durations.isNotEmpty()) { "A timeline needs at least one frame duration, and the list is empty." }
  durations.forEachIndexed { index, duration ->
    require(duration.isFinite() && duration > Duration.ZERO) {
      "Frame $index lasts $duration, and a frame lasts a positive, finite time."
    }
  }
}

private fun startsOf(durations: List<Duration>): LongArray {
  val starts = LongArray(durations.size)
  var sum = 0L
  durations.forEachIndexed { index, duration ->
    starts[index] = sum
    sum = saturatingAdd(sum, duration.inWholeNanoseconds)
  }
  return starts
}

private fun cycleOf(durations: List<Duration>): Long =
  durations.fold(0L) { sum, duration -> saturatingAdd(sum, duration.inWholeNanoseconds) }

private fun saturatingAdd(
  a: Long,
  b: Long,
): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

private fun saturatingTimes(
  a: Long,
  b: Long,
): Long = if (a != 0L && b > Long.MAX_VALUE / a) Long.MAX_VALUE else a * b
