package dev.jordond.filmstrip.transform.internal.audio

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioKernel
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.roundToInt

/**
 * Runs a list of [AudioKernel]s over one stream of interleaved audio, in order.
 *
 * The chain owns everything a host would otherwise work out for itself. It counts the position each kernel is handed,
 * takes the kernels' latency out of the head of the stream and hands it back from [drain], so the stream keeps its
 * length and its place on the timeline, and it converts 16-bit samples to the floats a kernel works in and back.
 *
 * [process], [drain] and [reset] belong to the thread that runs the audio and allocate nothing once the chain is
 * built, as long as no block is longer than `maxFrames`. [swap] may be called from any thread, and builds what the
 * new kernels need there.
 *
 * @param kernels The kernels to run, first to last.
 * @param channels How many channels the stream interleaves.
 * @param start The timeline index of the first frame the chain will be handed, from [timelineFrameOf].
 * @param maxFrames The longest block the host expects to hand over, which sizes the buffer the 16-bit path converts
 * through. A longer block grows it.
 * @param compensates Whether the kernels' latency is taken out of the stream. False for a host that has to hand back
 * every frame it is given the moment it is given it, which then plays the stream late by the latency and gets
 * nothing from [drain].
 */
@OptIn(ExperimentalAtomicApi::class)
@InternalFilmstripApi
public class KernelChain(
  kernels: List<AudioKernel>,
  private val channels: Int,
  start: Long,
  maxFrames: Int = DEFAULT_MAX_FRAMES,
  private val compensates: Boolean = true,
) {
  private val pending = AtomicReference<Stage?>(null)
  private var stage = Stage(kernels)
  private var position = start

  @Volatile
  private var compensated = if (compensates) stage.latency else 0
  private var toDrop = compensated
  private var owed = 0
  private var pad = EMPTY
  private var padAt = 0
  private var scratch = FloatArray(maxFrames * channels)

  init {
    require(channels > 0) { "A stream has at least one channel, got $channels." }
    stage.reset(start)
  }

  /**
   * How many frames the chain holds back from the head of the stream and hands over in [drain].
   *
   * It is the largest total latency of any kernels the chain has run, so a swap can raise it and never lowers it.
   * Zero for a chain that does not compensate.
   */
  public val latencyFrames: Int get() = compensated

  /**
   * Runs the kernels over the next [frames] frames of [buffer] in place.
   *
   * @return How many frames of output [buffer] now starts with. It is [frames] except at the head of a stream whose
   * kernels have latency, where the frames the latency delays are left out.
   */
  public fun process(
    buffer: FloatArray,
    frames: Int,
  ): Int {
    adoptPending()
    run(buffer, frames)
    val dropped = minOf(toDrop, frames)
    if (dropped > 0) {
      buffer.copyInto(buffer, 0, dropped * channels, frames * channels)
      toDrop -= dropped
      owed += dropped
    }
    return frames - dropped
  }

  /**
   * Runs the kernels over the next [frames] frames of 16-bit [buffer] in place, as [process] does for floats.
   *
   * A sample the kernels push past full scale is clamped to it.
   */
  public fun process(
    buffer: ShortArray,
    frames: Int,
  ): Int {
    val floats = scratchFor(frames)
    for (index in 0 until frames * channels) floats[index] = buffer[index] / FULL_SCALE
    val written = process(floats, frames)
    floats.writeInt16(buffer, written)
    return written
  }

  /**
   * Writes up to [maxFrames] of the frames the kernels still hold into [buffer], once the stream has ended.
   *
   * The host calls this until it answers zero. Together with what [process] handed back, the stream then has exactly
   * as many frames as it was given.
   *
   * @return How many frames were written, or zero when nothing is left.
   */
  public fun drain(
    buffer: FloatArray,
    maxFrames: Int,
  ): Int {
    adoptPending()
    // A stream shorter than the latency still has head frames to leave out before anything it is owed comes through.
    while (owed > 0 && toDrop > 0) {
      val skipped = minOf(toDrop, maxFrames)
      buffer.fill(0f, 0, skipped * channels)
      run(buffer, skipped)
      toDrop -= skipped
    }
    val frames = minOf(owed, maxFrames)
    if (frames == 0) return 0
    buffer.fill(0f, 0, frames * channels)
    run(buffer, frames)
    owed -= frames
    return frames
  }

  /**
   * Writes up to [maxFrames] of the frames the kernels still hold into 16-bit [buffer], as [drain] does for floats.
   */
  public fun drain(
    buffer: ShortArray,
    maxFrames: Int,
  ): Int {
    val floats = scratchFor(maxFrames)
    val written = drain(floats, maxFrames)
    floats.writeInt16(buffer, written)
    return written
  }

  /**
   * Starts a new stream at the timeline index [start], such as after a seek.
   *
   * Every kernel is reset, and what the chain held from the stream before is discarded.
   */
  public fun reset(start: Long) {
    adoptPending()
    position = start
    toDrop = compensated
    owed = 0
    pad.fill(0f)
    padAt = 0
    stage.reset(start)
  }

  /**
   * Replaces the kernels with [kernels] from the next block on.
   *
   * The block being processed finishes on the kernels it started on. The new kernels are reset to where the stream is
   * and start with no state, and the stream keeps its place on the timeline whatever their latency.
   */
  public fun swap(kernels: List<AudioKernel>) {
    val next = Stage(kernels)
    if (compensates) next.pad = FloatArray(maxOf(compensated - next.latency, 0) * channels)
    pending.store(next)
  }

  private fun adoptPending() {
    val next = pending.exchange(null) ?: return
    stage = next
    padAt = 0
    if (!compensates) {
      next.reset(position)
      return
    }
    if (next.latency > compensated) {
      // The new kernels delay the stream further than anything before them, so that many more frames are left out
      // here and handed back in drain.
      toDrop += next.latency - compensated
      compensated = next.latency
      pad = EMPTY
    } else {
      // The new kernels delay the stream less, so the chain delays it by the difference to hold its place. The buffer
      // was sized when the swap was asked for, and is only built here when another swap moved the latency since.
      val size = (compensated - next.latency) * channels
      pad = if (next.pad.size == size) next.pad else FloatArray(size)
    }
    next.reset(position)
  }

  private fun run(
    buffer: FloatArray,
    frames: Int,
  ) {
    stage.process(buffer, frames, position)
    if (pad.isNotEmpty()) {
      for (index in 0 until frames * channels) {
        val delayed = pad[padAt]
        pad[padAt] = buffer[index]
        buffer[index] = delayed
        padAt = if (padAt + 1 == pad.size) 0 else padAt + 1
      }
    }
    position += frames
  }

  private fun scratchFor(frames: Int): FloatArray {
    if (scratch.size < frames * channels) scratch = FloatArray(frames * channels)
    return scratch
  }

  private fun FloatArray.writeInt16(
    into: ShortArray,
    frames: Int,
  ) {
    for (index in 0 until frames * channels) {
      into[index] = (this[index] * FULL_SCALE).roundToInt().coerceIn(MIN_INT16, MAX_INT16).toShort()
    }
  }

  // The kernels of one swap, each with the latency of everything ahead of it. A kernel behind a delay is handed the
  // position of the frames it is given, which is the stream's position less that delay.
  private class Stage(
    kernels: List<AudioKernel>,
  ) {
    private val kernels = kernels.toTypedArray()
    private val upstream = IntArray(kernels.size)
    val latency: Int
    var pad: FloatArray = EMPTY

    init {
      var total = 0
      this.kernels.forEachIndexed { index, kernel ->
        val own = kernel.latencyFrames
        require(own >= 0) { "A kernel's latency cannot be negative, got $own." }
        upstream[index] = total
        total += own
      }
      latency = total
    }

    fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      for (index in kernels.indices) kernels[index].process(buffer, frames, position - upstream[index])
    }

    fun reset(position: Long) {
      for (index in kernels.indices) kernels[index].reset(position - upstream[index])
    }
  }

  private companion object {
    const val DEFAULT_MAX_FRAMES = 4096
    const val FULL_SCALE = 32768f
    const val MIN_INT16 = -32768
    const val MAX_INT16 = 32767
    val EMPTY = FloatArray(0)
  }
}
