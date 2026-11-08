package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.sourceLengthOf
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.DurationUnit

/**
 * Puts a clip's frames on the composition's clock before the chain behind it draws them.
 *
 * A frame reader decodes one media item on its own, so the timestamps reaching its effects run from
 * the start of that item. The chain it runs was lowered against the composition's timeline, where
 * media3 offsets an item's frames by where it sits before any effect sees them, and an effect that
 * travels over a span reads that time to decide what to draw. Adding [offset] at the head of a
 * reader's chain is what makes both readings the same number.
 *
 * The frame is handed straight on, so this costs a link in the chain and no drawing. [itemClock]
 * goes at the tail of the same chain and takes the frame back off this clock, so the reader at the
 * end of it sees the item's own time again.
 *
 * A clip laid by the frames it shows is stamped the way its export writes it: each frame it shows lands where it is
 * written, and a frame between two of those lands between them in proportion. Past the last one shown the clock runs
 * at [speed]. A clip with none runs at [speed] throughout.
 *
 * @property offset Where the clip starts on the composition's timeline.
 * @property speed How fast the clip plays, since a reader's frames are measured in source time.
 * @param shown Each frame the clip writes, as how far into the clip it is written paired with where it sits in the
 * item, in order.
 */
internal class CompositionClock(
  private val offset: Duration,
  private val speed: Double = 1.0,
  shown: List<Pair<Duration, Duration>> = emptyList(),
) : GlEffect {
  private val writtenUs = LongArray(shown.size) { shown[it].first.inWholeMicroseconds }
  private val positionsUs = LongArray(shown.size) { shown[it].second.inWholeMicroseconds }

  /**
   * The link that takes a frame back off this clock, for the end of the chain this clock heads.
   *
   * A frame reader compares the time its last frame carries with the position it is asked for, and
   * answers with that frame when the two match. On the composition's clock the item's first frame
   * carries the clip's start, so without this a read that many milliseconds into the item is
   * answered with the first frame.
   */
  val itemClock: GlEffect = GlEffect { _, _ -> RestampShaderProgram(::itemTimeUsOf) }

  /**
   * The composition time a frame [itemTime] into the clip's item is drawn at.
   */
  fun compositionTimeOf(itemTime: Duration): Duration {
    if (positionsUs.isEmpty()) return offset + outputLengthOf(itemTime, speed)
    val itemUs = itemTime.inWholeMicroseconds
    return offset + mapped(itemUs, from = positionsUs, to = writtenUs) { outputLengthOf(it, speed) }.microseconds
  }

  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = RestampShaderProgram { compositionTimeOf(it.microseconds).inWholeMicroseconds }

  // A sped up clip with no shown frames packs several item microseconds into each one the head stamps, so a frame can
  // come back up to its speed in microseconds before where it was decoded. A shown frame always comes back on its own
  // position, and the item's first frame on zero.
  private fun itemTimeUsOf(compositionTimeUs: Long): Long {
    val intoClip = compositionTimeUs - offset.inWholeMicroseconds
    if (positionsUs.isEmpty()) {
      return sourceLengthOf(intoClip.microseconds, speed).toDouble(DurationUnit.MICROSECONDS).roundToLong()
    }
    return mapped(intoClip, from = writtenUs, to = positionsUs) { sourceLengthOf(it, speed) }
  }
}

/**
 * Where [valueUs] lands when each of [from] lands on the same entry of [to], in between in proportion, and past the
 * last by [beyond] of how far past it [valueUs] sits.
 */
private fun mapped(
  valueUs: Long,
  from: LongArray,
  to: LongArray,
  beyond: (Duration) -> Duration,
): Long {
  val found = from.binarySearch(valueUs)
  val index = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
  val past = valueUs - from[index]
  if (past == 0L) return to[index]
  if (index == from.lastIndex || past < 0) {
    return to[index] + beyond(past.microseconds).toDouble(DurationUnit.MICROSECONDS).roundToLong()
  }
  val share = past.toDouble() / (from[index + 1] - from[index])
  return to[index] + (share * (to[index + 1] - to[index])).roundToLong()
}

/**
 * Restamps each frame and forwards the texture it arrived on.
 *
 * Everything but the timestamp is the pass-through media3 already ships, which owns no texture of
 * its own and releases and flushes as the chain around it expects.
 */
private class RestampShaderProgram(
  private val restamp: (Long) -> Long,
) : PassthroughShaderProgram() {
  override fun queueInputFrame(
    glObjectsProvider: GlObjectsProvider,
    inputTexture: GlTextureInfo,
    presentationTimeUs: Long,
  ) {
    super.queueInputFrame(glObjectsProvider, inputTexture, restamp(presentationTimeUs))
  }
}
