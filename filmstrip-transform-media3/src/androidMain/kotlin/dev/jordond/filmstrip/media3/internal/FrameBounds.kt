package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolvedClip
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
 * for.
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
 * The speed that plays [input] microseconds for exactly [output] of them.
 *
 * media3 divides by the speed as a float, rounds the answer in one place and floors it in another. The nearest float
 * can leave either reading a microsecond out, so this walks a float at a time to one that both read as [output].
 */
private fun speedFor(
  input: Long,
  output: Long,
): Float {
  var speed = (input.toDouble() / output).toFloat()
  repeat(SPEED_STEPS) {
    val played = input / speed.toDouble()
    speed =
      when {
        played < output -> Math.nextDown(speed)
        played >= output + HALF_MICROSECOND -> Math.nextUp(speed)
        else -> return speed
      }
  }
  return speed
}

private const val SPEED_STEPS = 64
private const val HALF_MICROSECOND = 0.5
