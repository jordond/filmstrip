package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * Hands a frame on again at each later slot that shows it, so an item writes a frame on every slot it covers.
 *
 * media3 decodes one frame per sample. A sample shown on several slots, because its source runs slower than the output
 * or the clip is slowed, reaches this effect once, written on the first of them. The frame is handed on at that time
 * and again at each time [ShownFrames.repeatsUs] lists for it, and every later effect draws it at the time it is handed
 * on at.
 *
 * A frame reaches this effect stamped on the composition's timeline, so a frame is matched by how far its stamp sits
 * past [startUs], where the item starts. Nothing is remembered between frames, which is what lets a seek or a redraw
 * hand it any frame of the item.
 *
 * While [pin] holds a slot that one of a frame's slots is, the frame is handed on at that slot alone. That is how a
 * paused player shows a repeat: media3 drops every frame before a seek's position, so the seek goes to the slot the
 * sample is written on and the frame it lands on is drawn on the pinned slot.
 *
 * Two effects are equal when they repeat the same slots, which is what lets a standing graph match a re-lowering.
 *
 * @property repeatsUs The later slots of each sample shown, keyed by where the sample is written, both in microseconds
 * into the item.
 * @property startUs Where the item starts on the composition's timeline, in microseconds.
 * @property toleranceUs How far a frame may sit from the time it is keyed at and still be that sample, in microseconds.
 * @property pin The slot a paused player holds, or null where every frame is handed on at each of its slots.
 */
internal class SlotRepeat(
  val repeatsUs: Map<Long, List<Long>>,
  val startUs: Long,
  val toleranceUs: Long,
  val pin: SlotPin? = null,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = SlotRepeatProgram(repeatsUs, startUs, toleranceUs, pin)

  override fun equals(other: Any?): Boolean =
    other is SlotRepeat && repeatsUs == other.repeatsUs && startUs == other.startUs && toleranceUs == other.toleranceUs

  override fun hashCode(): Int = 31 * (31 * repeatsUs.hashCode() + startUs.hashCode()) + toleranceUs.hashCode()

  override fun toString(): String = "SlotRepeat(startUs=$startUs, repeats=${repeatsUs.size})"
}

/**
 * The slot a paused player holds when that slot is a repeat, which every [SlotRepeat] of a graph reads.
 *
 * It is written on the engine's threads and read on media3's frame thread.
 */
internal class SlotPin {
  @Volatile
  var atUs: Long? = null
}

/**
 * How far a frame may sit from its key at an output rate of [frameRate], in microseconds.
 *
 * A slot is a whole number of microseconds and an item's start and its frames are each rounded to one, so this is far
 * wider than they can drift and a quarter of a slot narrower than where the next sample is written.
 */
internal fun slotRepeatToleranceUs(frameRate: Int): Long = MICROS_PER_SECOND / frameRate / 4

/**
 * Runs one [SlotRepeat].
 *
 * It matches each frame to the key nearest how far the frame sits past the item's start. It hands the texture on once
 * per slot and returns it to the effect before it only when the last of those has been released.
 */
internal class SlotRepeatProgram(
  repeatsUs: Map<Long, List<Long>>,
  private val startUs: Long,
  private val toleranceUs: Long,
  private val pin: SlotPin? = null,
) : PassthroughShaderProgram() {
  private val keys = repeatsUs.keys.sorted().toLongArray()
  private val repeats = keys.map { repeatsUs.getValue(it) }
  private var handedOn = 0

  override fun queueInputFrame(
    glObjectsProvider: GlObjectsProvider,
    inputTexture: GlTextureInfo,
    presentationTimeUs: Long,
  ) {
    val slots = listOf(presentationTimeUs) + repeatsOf(presentationTimeUs - startUs).map { startUs + it }
    val pinned = pin?.atUs?.let { held -> slots.firstOrNull { abs(it - held) <= toleranceUs } }
    val handOn = if (pinned != null) listOf(pinned) else slots
    handedOn = handOn.size
    handOn.forEach { super.queueInputFrame(glObjectsProvider, inputTexture, it) }
  }

  override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
    if (--handedOn == 0) super.releaseOutputFrame(outputTexture)
  }

  override fun flush() {
    handedOn = 0
    super.flush()
  }

  // The repeats of the key nearest [intoUs], or none when no key sits within the tolerance.
  private fun repeatsOf(intoUs: Long): List<Long> {
    val found = keys.binarySearch(intoUs)
    if (found >= 0) return repeats[found]
    val after = -found - 1
    val nearest = listOf(after - 1, after).filter { it in keys.indices }.minByOrNull { abs(keys[it] - intoUs) }
    return if (nearest != null && abs(keys[nearest] - intoUs) <= toleranceUs) repeats[nearest] else emptyList()
  }
}

private const val MICROS_PER_SECOND = 1_000_000L
