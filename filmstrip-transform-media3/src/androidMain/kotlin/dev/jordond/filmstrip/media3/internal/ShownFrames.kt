package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.media3.internal.bounds.FrameBounds
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import kotlin.time.Duration

/**
 * The source samples one grid item writes, and the stretches [SlotPace] plays the item in to write each on its slot.
 *
 * Every slot the clip covers shows the sample [nearestSourceFrame] picks for its [sourceTimeOfSlot], and a sample is
 * written on the first slot that shows it. Each later slot showing it is a repeat, which [SlotRepeat] writes. The item
 * opens on the first sample shown and closes where the last one shown stops showing, on the sample after it or the end
 * of the track, so nothing past that is decoded. The last one holds until the item has run for [lengthUs].
 *
 * The samples between two shown ones are written in the microseconds right after the first of them. media3's frame
 * rate cap keeps a frame only at or past its next tick, a whole number of microseconds per output frame past the first
 * frame it kept, so it drops them there. A slot is not a whole number of microseconds, so the slots of a long item run
 * ahead of those ticks, by a third of a microsecond per slot at 30 fps. Once that reaches a slot's length, about 55
 * minutes into a sped up item at 30 fps and 7 at 60 fps, the cap keeps them instead, which is media3's limit.
 *
 * @property opens Where the item opens on the source's clock, which is the first sample shown.
 * @property closes Where the item closes on the source's clock, where the last sample shown stops showing.
 * @property inputsUs Where each stretch starts, in microseconds into the item. The first is zero.
 * @property outputsUs Where each stretch is written, in microseconds into the item. The first is zero.
 * @property lengthUs How long the item runs for, in microseconds, which is the slots its clip covers.
 * @property shown Each sample shown, as how far into the item it is written paired with where it sits in the item.
 * @property repeatsUs The later slots each sample shown fills, keyed by where the sample is written and holding how far
 * into the item each of those slots is. A sample shown on one slot has no entry.
 */
internal class ShownFrames(
  val opens: Duration,
  val closes: Duration,
  val inputsUs: List<Long>,
  val outputsUs: List<Long>,
  val lengthUs: Long,
  val shown: List<Pair<Long, Long>>,
  val repeatsUs: Map<Long, List<Long>> = emptyMap(),
) {
  /**
   * How long the item decodes for, in microseconds.
   */
  val decodedUs: Long get() = (closes - opens).inWholeMicroseconds

  companion object {
    /**
     * The samples [clip] writes on the output grid at [frameRate], out of [bounds], the samples its window decodes.
     * Null when the clip covers no slot.
     */
    fun of(
      clip: ResolvedClip,
      bounds: FrameBounds,
      frameRate: Int,
    ): ShownFrames? {
      val slots = outputSlotsOf(clip.span, frameRate)
      if (slots.isEmpty()) return null
      val samples = bounds.samples

      val slotsShowing = LinkedHashMap<Int, MutableList<Long>>()
      slots.forEach { slot ->
        val time = sourceTimeOfSlot(clip.start, clip.span.start, clip.speed, slot, frameRate, clip.runStart)
        slotsShowing.getOrPut(checkNotNull(nearestSourceFrame(samples, time))) { mutableListOf() } += slot
      }
      val indices = slotsShowing.keys.toList()
      val opens = samples[indices.first()]
      val writtenFrom = outputSlotTime(slots.first, frameRate)

      val inputs = mutableListOf<Long>()
      val outputs = mutableListOf<Long>()
      val shown = mutableListOf<Pair<Long, Long>>()
      val repeats = mutableMapOf<Long, List<Long>>()

      fun intoItem(slot: Long) = (outputSlotTime(slot, frameRate) - writtenFrom).inWholeMicroseconds
      indices.forEachIndexed { order, index ->
        val position = (samples[index] - opens).inWholeMicroseconds
        val showing = slotsShowing.getValue(index)
        val written = intoItem(showing.first())
        if (showing.size > 1) repeats[written] = showing.drop(1).map(::intoItem)
        inputs += position
        outputs += written
        shown += written to position
        val skipped = (indices.getOrNull(order + 1) ?: return@forEachIndexed) - index - 1
        if (skipped > 0) {
          inputs += (samples[index + skipped] - opens).inWholeMicroseconds
          outputs += written + skipped
        }
      }

      val last = indices.last()
      return ShownFrames(
        opens = opens,
        closes = samples.getOrNull(last + 1) ?: bounds.end,
        inputsUs = inputs,
        outputsUs = outputs,
        lengthUs = (outputSlotTime(slots.last + 1, frameRate) - writtenFrom).inWholeMicroseconds,
        shown = shown,
        repeatsUs = repeats,
      )
    }
  }
}
