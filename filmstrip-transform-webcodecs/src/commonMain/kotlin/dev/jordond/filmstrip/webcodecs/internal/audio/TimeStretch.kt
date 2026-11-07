package dev.jordond.filmstrip.webcodecs.internal.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Plays planar Float32 audio at another speed without moving its pitch, using waveform similarity overlap-add.
 *
 * Output is built from Hann windowed grains laid half a grain apart. Each grain is read from the source around where
 * its middle lands at [speed], at the nearby offset that best continues the grain before it, which is what keeps a tone
 * in phase across the overlap. Every channel reads from the offset chosen on their mix, so a stereo image holds.
 *
 * The caller decides how much output it wants and asks for exactly that, so a length never comes from the grain count.
 * A grain decides only from the source it reads and the grain before it, so the output does not depend on how the
 * input or the pulls were cut. Pulling past the end of the source reads silence. At a speed of 1 the input comes back
 * untouched.
 *
 * @param speed How many seconds of source play through one second of output.
 * @param channelCount How many channels each push carries and each pull returns.
 * @param sampleRate The rate of the input and the output, which sizes the grain.
 * @param sourceFrames How many frames this source runs to, which keeps the last grains from reading past it.
 */
internal class TimeStretch(
  private val speed: Double,
  private val channelCount: Int,
  sampleRate: Int,
  private val sourceFrames: Long,
) {
  private val hop = (sampleRate * GRAIN_SECONDS / 2).roundToInt().coerceAtLeast(1)
  private val grain = hop * 2
  private val radius = (sampleRate * SEARCH_SECONDS).roundToInt().coerceAtLeast(1)
  private val stride = (sampleRate / SEARCH_RATE).coerceAtLeast(1)
  private val rise = DoubleArray(hop) { 0.5 - 0.5 * cos(PI * it / hop) }
  private val fall = DoubleArray(hop) { 1.0 - rise[it] }

  private var input = Array(channelCount) { FloatArray(grain * 2) }
  private var mix = FloatArray(grain * 2)
  private var inputBase = 0L
  private var inputEnd = 0L
  private var keepFrom = 0L
  private var finished = false

  private val tail = Array(channelCount) { DoubleArray(hop) }
  private val template = FloatArray(hop)
  private var grainIndex = 0L

  private var ready = Array(channelCount) { FloatArray(hop * 2) }
  private var readyStart = 0
  private var readyEnd = 0

  /**
   * How many of the next input frames no grain reads, which a caller may hand to [skip] instead of copying.
   */
  val skippableFrames: Long get() = max(0L, keepFrom - inputEnd)

  /**
   * Adds the next [frames] of input, read from [offset] in each of [channels].
   */
  fun push(
    channels: Array<FloatArray>,
    frames: Int,
    offset: Int = 0,
  ) {
    check(!finished) { "A finished stretch takes no more input." }
    val skipped = min(skippableFrames, frames.toLong()).toInt()
    skip(skipped)
    val count = frames - skipped
    if (count == 0) return

    makeRoom(count)
    val at = (inputEnd - inputBase).toInt()
    for (channel in 0 until channelCount) {
      channels[channel].copyInto(input[channel], at, offset + skipped, offset + skipped + count)
    }
    for (index in at until at + count) {
      var sum = 0.0
      for (channel in 0 until channelCount) sum += input[channel][index]
      mix[index] = (sum / channelCount).toFloat()
    }
    inputEnd += count
  }

  /**
   * Moves past [frames] of input no grain reads, without holding them.
   */
  fun skip(frames: Int) {
    inputEnd += frames
  }

  /**
   * Says the input is over, so the grains left read silence past where it stopped.
   */
  fun finish() {
    finished = true
  }

  /**
   * Builds grains until [frames] of output are waiting.
   *
   * @return False when more input has to arrive first. Once [finish] was called this is always true.
   */
  fun canPull(frames: Int): Boolean {
    while (readyEnd - readyStart < frames) {
      val built = if (speed == 1.0) passThrough() else nextGrain()
      if (!built) return false
    }
    return true
  }

  /**
   * Takes the next [frames] of output, one array per channel.
   *
   * @throws IllegalStateException When [canPull] would answer false for [frames].
   */
  fun pull(frames: Int): Array<FloatArray> {
    check(canPull(frames)) { "A stretch needs more input before it can give $frames frames." }
    val out = Array(channelCount) { ready[it].copyOfRange(readyStart, readyStart + frames) }
    readyStart += frames
    return out
  }

  private fun passThrough(): Boolean {
    val end = keepFrom + hop
    if (!finished && inputEnd < end) return false
    val out = claimReady()
    val available = (min(end, inputEnd) - keepFrom).toInt().coerceAtLeast(0)
    val at = (keepFrom - inputBase).toInt()
    for (channel in 0 until channelCount) {
      if (available > 0) input[channel].copyInto(ready[channel], out, at, at + available)
      ready[channel].fill(0f, out + available, out + hop)
    }
    keepFrom = end
    return true
  }

  private fun nextGrain(): Boolean {
    if (grainIndex == 0L) return firstGrain()

    val range = searchRange(grainIndex)
    if (!finished && inputEnd < min(range.last + grain, sourceFrames)) return false

    val start = bestStart(range)
    val out = claimReady()
    val at = (start - inputBase).toInt()
    val available = (inputEnd - start).coerceIn(0L, grain.toLong()).toInt()
    for (channel in 0 until channelCount) {
      val source = input[channel]
      val into = ready[channel]
      val held = tail[channel]
      for (index in 0 until hop) {
        val sample = if (index < available) source[at + index].toDouble() else 0.0
        into[out + index] = (held[index] + sample * rise[index]).toFloat()
      }
      for (index in 0 until hop) {
        val sample = if (hop + index < available) source[at + hop + index].toDouble() else 0.0
        held[index] = sample * fall[index]
      }
    }
    for (index in 0 until hop) {
      template[index] = if (hop + index < available) mix[at + hop + index] else 0f
    }
    grainIndex++
    keepFrom = searchRange(grainIndex).first
    return true
  }

  // The grain centred on the first output frame. Only its falling half is ever heard, and it opens on the source's
  // first frame at full level, so the output has no fade in.
  private fun firstGrain(): Boolean {
    if (!finished && inputEnd < min(hop.toLong(), sourceFrames)) return false

    val available = (inputEnd - inputBase).coerceIn(0L, hop.toLong()).toInt()
    for (channel in 0 until channelCount) {
      for (index in 0 until hop) {
        val sample = if (index < available) input[channel][index].toDouble() else 0.0
        tail[channel][index] = sample * fall[index]
      }
    }
    for (index in 0 until hop) {
      template[index] = if (index < available) mix[index] else 0f
    }
    grainIndex = 1L
    keepFrom = searchRange(grainIndex).first
    return true
  }

  // Where grain [index] may open, which is around the source frame its middle lands on, kept inside the source.
  private fun searchRange(index: Long): LongRange {
    val middle = (index * hop * speed).roundToLong()
    val centre = max(middle - hop, 0L)
    val latest = max(sourceFrames - grain, 0L)
    val last = min(centre + radius, latest)
    return min(max(centre - radius, 0L), last)..last
  }

  // The opening in [range] whose first half best matches what the grain before it has left to play. Silence matches
  // nothing, so the nominal opening stands there and the choice stays the same on every run.
  private fun bestStart(range: LongRange): Long {
    val middle = (grainIndex * hop * speed).roundToLong()
    var best = (middle - hop).coerceIn(range.first, range.last)
    var bestScore = scoreOf(best)
    var candidate = range.first
    while (candidate <= range.last) {
      val score = scoreOf(candidate)
      if (score > bestScore) {
        best = candidate
        bestScore = score
      }
      candidate++
    }
    return best
  }

  // A normalized correlation with its sign kept, read on every [stride]th frame since a match sits well under that.
  private fun scoreOf(candidate: Long): Double {
    val length = min(hop.toLong(), inputEnd - candidate).toInt()
    if (length <= 0) return 0.0
    val at = (candidate - inputBase).toInt()
    var dot = 0.0
    var energy = 0.0
    var index = 0
    while (index < length) {
      val sample = mix[at + index].toDouble()
      dot += sample * template[index]
      energy += sample * sample
      index += stride
    }
    return if (energy < SILENCE_ENERGY) 0.0 else dot * abs(dot) / energy
  }

  // Makes room for one more [hop] of output and says where it goes.
  private fun claimReady(): Int {
    val waiting = readyEnd - readyStart
    val capacity = ready[0].size
    if (readyEnd + hop > capacity) {
      val size = if (waiting + hop > capacity) max(capacity * 2, waiting + hop) else capacity
      ready =
        Array(channelCount) { channel ->
          val next = if (size == capacity) ready[channel] else FloatArray(size)
          ready[channel].copyInto(next, 0, readyStart, readyEnd)
          next
        }
      readyStart = 0
      readyEnd = waiting
    }
    val at = readyEnd
    readyEnd += hop
    return at
  }

  // Drops what no grain reads any more, then grows the buffer if [count] more frames still do not fit.
  private fun makeRoom(count: Int) {
    val from = min(keepFrom, inputEnd)
    val kept = (inputEnd - from).toInt()
    if (kept == 0) inputBase = from
    val shift = (from - inputBase).toInt()
    val needed = kept + count
    val capacity = mix.size
    if (shift + needed <= capacity) return
    val size = if (needed > capacity) max(capacity * 2, needed) else capacity
    input =
      Array(channelCount) { channel ->
        val next = if (size == capacity) input[channel] else FloatArray(size)
        input[channel].copyInto(next, 0, shift, shift + kept)
        next
      }
    val nextMix = if (size == capacity) mix else FloatArray(size)
    mix.copyInto(nextMix, 0, shift, shift + kept)
    mix = nextMix
    inputBase = from
  }

  private companion object {
    /**
     * Forty milliseconds, long enough to hold a few periods of anything voiced and short enough that a transient does
     * not smear into a repeat.
     */
    const val GRAIN_SECONDS = 0.04

    /**
     * How far either way a grain may move to line up, which holds a whole period of anything above about 80 Hz.
     */
    const val SEARCH_SECONDS = 0.012

    /**
     * The rate the search reads at. Alignment is decided by the low end of the signal, which this keeps.
     */
    const val SEARCH_RATE = 24_000

    /**
     * Under this a candidate is silence as far as a Float32 sample can tell, and it scores nothing.
     */
    const val SILENCE_ENERGY = 1e-12
  }
}
