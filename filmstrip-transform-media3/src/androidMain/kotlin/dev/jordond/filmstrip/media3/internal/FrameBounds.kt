package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import kotlin.math.ceil
import kotlin.time.Duration

/**
 * The video samples one clip's window decodes, read off its source's container.
 *
 * Every time is on the source's own clock. media3 opens a clipped item on the earliest sample at or after its start and
 * stops decoding at [end], so an item's decoded length runs from [first] to [end].
 *
 * @property first The earliest sample at or after the clip's start, in presentation order.
 * @property last The latest sample before [end], in presentation order.
 * @property end Where the decoded window stops, which is the clip's end or the source's own end when that comes first.
 */
internal class FrameBounds(
  val first: Duration,
  val last: Duration,
  val end: Duration,
)

/**
 * The window a clip cuts from its source, which is all its [FrameBounds] depend on.
 */
internal data class ClipWindow(
  val source: MediaSource,
  val start: Duration,
  val end: Duration,
)

/**
 * The [FrameBounds] of every clip a composition lays on the output frame grid.
 *
 * Read once per plan by `readFrameBounds` and handed to every lowering of that plan, so an export and a preview of it
 * lay the same items. A clip missing from it, which is every clip in [None], opens on its own start and is not held
 * past the end of what it decodes.
 */
@InternalFilmstripApi
public class Media3FrameBounds internal constructor(
  private val windows: Map<ClipWindow, FrameBounds>,
) {
  /**
   * The bounds read for [clip]'s window, or null when none were.
   */
  internal operator fun get(clip: ResolvedClip): FrameBounds? = windows[ClipWindow(clip.source, clip.start, clip.end)]

  @InternalFilmstripApi
  public companion object {
    /**
     * Bounds for no clip at all.
     *
     * For a lowering that never reaches a container, which lays each clip from its own start.
     */
    public val None: Media3FrameBounds = Media3FrameBounds(emptyMap())
  }
}

/**
 * Runs an item at its own speed up to its last frame and slower after it, so that frame is held until the item has run
 * for the length its slots ask for.
 *
 * Only what comes after [fromUs] is slowed, so every frame keeps its own time, the last one included. media3 reads the
 * held length twice, once rounding and once flooring, and the speed is the one both readings land exactly on the length
 * for. A hold of more than a few seconds can run past the length by up to [speedStepUs], which is as close as media3's
 * float speed gets.
 *
 * @property fromUs Where the last frame sits, in microseconds from the start of the item.
 * @param decodedUs How long the item decodes for, in microseconds.
 * @param lengthUs How long the item has to run for, in microseconds.
 */
internal class HeldLastFrame(
  val fromUs: Long,
  decodedUs: Long,
  lengthUs: Long,
) : SpeedProvider {
  private val speed = speedFor(decodedUs - fromUs, lengthUs - fromUs)

  override fun getSpeed(timeUs: Long): Float = if (timeUs < fromUs) 1f else speed

  override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = if (timeUs < fromUs) fromUs else C.TIME_UNSET
}

/**
 * The speed that plays [input] microseconds for exactly [output] of them, or for as little past it as media3 allows.
 *
 * media3 divides by the speed as a float, rounds the answer in one place and floors it in another, so both readings
 * land on [output] only when the played length sits within half a microsecond past it. This walks a float at a time to
 * the fastest speed that plays for at least [output], which is the one that lands whenever any float does.
 *
 * Past about four seconds of [output], one float step moves the played length by more than half a microsecond and no
 * float may land. That is media3's limit, since it only takes a float, and the frame is then held long rather than
 * short, by at most [speedStepUs].
 *
 * Requires [input] to be positive and shorter than [output], since a hold only slows an item down.
 */
private fun speedFor(
  input: Long,
  output: Long,
): Float {
  require(input > 0 && input < output) { "input ($input) must be positive and shorter than output ($output)" }
  var speed = (input.toDouble() / output).toFloat()
  while (input / speed.toDouble() < output) speed = Math.nextDown(speed)
  // Clamps to media3's float: where no speed lands both readings, this stops on the one that plays long by a step.
  while (input / Math.nextUp(speed).toDouble() >= output) speed = Math.nextUp(speed)
  return speed
}

/**
 * The most a hold of [outputUs] can play past it, which is one step between two neighbouring float speeds.
 *
 * A float moves by at most one part in 2^23 of itself from one value to the next, and the played length moves with it.
 * Under 2^22 microseconds this is under half a microsecond, which is why every shorter hold lands exactly.
 */
internal fun speedStepUs(outputUs: Long): Long = ceil(outputUs * FLOAT_STEP / (1 - FLOAT_STEP)).toLong()

private val FLOAT_STEP = Math.ulp(1f).toDouble()
