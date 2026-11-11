@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.transform.internal.audio.KernelChain
import dev.jordond.filmstrip.transform.internal.audio.timelineFrameOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.runsAs
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alignOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.CoreAudioTypes.AudioBuffer
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsNonInterleaved
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.MediaToolbox.MTAudioProcessingTapGetSourceAudio
import platform.MediaToolbox.MTAudioProcessingTapRef
import platform.MediaToolbox.kMTAudioProcessingTapFlag_EndOfStream
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The kernels of every span at the format a tap was prepared for, and the buffers a block is run through them in.
 *
 * AVFoundation places each block a tap hands back at the time of the source it asked for, so a tap cannot hand back
 * fewer frames to take a kernel's latency out. A stage that reads ahead asks for the source past the block as well,
 * which a reader hands over, feeds its kernels that far and hands back the frames that belong in the block. A stage
 * that does not read ahead runs its kernels on the block alone, and what they delay plays that much late.
 *
 * Every frame the kernels make is written to a ring at its own place on the timeline and read back from there, so a
 * span's last frames land where they belong whichever block they were made in.
 *
 * A read that reaches the end of the track ends the track there, whatever is left to hand back, so the source is
 * read ahead only as far as the frame before the track's last. That is AVFoundation's limit, and kernels that hold
 * frames back are fed silence in place of that one frame.
 *
 * @param readsUntil The composition time the track's audio ends at, which the source is read ahead as far as and the
 * kernels' latency taken out with it. Null to read nothing ahead.
 */
internal class TapStage private constructor(
  private val format: AudioFormat,
  private val planar: Boolean,
  private val maxFrames: Int,
  readsUntil: Duration?,
  spans: List<TapSpan>,
  mix: TapMix?,
) {
  private val channels = format.channelCount
  private val readsAhead = readsUntil != null

  // The first frame that is never read ahead of its own block: the last whole frame the track holds.
  private val unread = readsUntil?.let { it.inWholeNanoseconds * format.sampleRate / NANOS_PER_SECOND - 1 } ?: 0
  private val starts = LongArray(spans.size) { timelineFrameOf(spans[it].range.start, format.sampleRate) }
  private val ends =
    LongArray(spans.size) { index ->
      spans[index].range.endExclusive?.let { timelineFrameOf(it, format.sampleRate) } ?: Long.MAX_VALUE
    }
  private val chains = Array(spans.size) { index -> chainOf(spans[index].effects, starts[index]) }

  // The effects each span and the mix are running, which a swap is compared with. Only the thread that swaps reads
  // or writes them.
  private val effects = Array(spans.size) { spans[it].effects }
  private var mixEffects = mix?.effects.orEmpty()

  // How far past a block the source is read, which is the longest any span's kernels hold a frame back.
  private val ahead = if (readsAhead) chains.maxOfOrNull { it.latencyFrames } ?: 0 else 0
  private val source = if (ahead > 0) TapSource(planar, channels, maxFrames + ahead) else null
  private val capacity = maxFrames + ahead
  private val ring = FloatArray(capacity * channels)
  private val block = FloatArray(capacity * channels)

  @Volatile
  private var gains: Array<GainCurve?> = gainsOf(spans)

  // The timeline frame each span's next frame of output belongs on.
  private val outAt = LongArray(spans.size)

  private val mixChain = mix?.let { chainOf(it.effects, 0) }

  // The mix's effects run over silence beside the track's own audio, on a track that leaves their own sound out.
  private val silentChain = mix?.takeUnless { it.carriesAdditive }?.let { chainOf(it.effects, 0) }
  private val silence = if (silentChain != null) FloatArray(maxFrames * channels) else EMPTY

  // The timeline frame the next block opens on when the track plays on without a seek, the frame the kernels have
  // been fed up to, and the span that frame was in.
  private var next = Long.MIN_VALUE
  private var fed = 0L
  private var current = -1

  /**
   * Swaps in the kernels and gains of [spans] where a span opens on the frame a standing one does, and the kernels of
   * [mix]. Effects that are the ones already running keep their kernels. A stage that reads ahead was sized for the
   * kernels it was built with and takes no swap.
   */
  fun swap(
    spans: List<TapSpan>,
    mix: TapMix?,
  ) {
    if (readsAhead) return
    if (mix != null && !mix.effects.runsAs(mixEffects)) {
      mixEffects = mix.effects
      mixChain?.swap(mix.effects.map { it.effect.newKernel(format) })
      silentChain?.swap(mix.effects.map { it.effect.newKernel(format) })
    }
    if (spans.size != chains.size) return
    val curves = gains.copyOf()
    spans.forEachIndexed { index, span ->
      if (timelineFrameOf(span.range.start, format.sampleRate) == starts[index]) {
        if (!span.effects.runsAs(effects[index])) {
          effects[index] = span.effects
          chains[index].swap(span.effects.map { it.effect.newKernel(format) })
        }
        curves[index] = span.gain?.let { GainCurve.of(it, format.sampleRate) }
      }
    }
    gains = curves
  }

  /**
   * Frees the buffer the source ahead of a block is read into.
   */
  fun close() {
    source?.free()
  }

  /**
   * Reads the source of the next [frames] frames into [buffers] and leaves what the spans make of it there.
   *
   * @return how many frames [buffers] now holds, or a negative number when AVFoundation handed over no source.
   * @throws IllegalStateException when the kernels hold back more frames than AVFoundation hands over ahead of a
   * block.
   */
  fun run(
    tap: MTAudioProcessingTapRef?,
    frames: Int,
    buffers: CPointer<AudioBufferList>,
    scratch: TapScratch,
  ): Int {
    if (!scratch.read(tap, frames, buffers)) return NO_SOURCE
    val wanted = scratch.frames.value.toInt()
    val flags = scratch.flags.value
    val opens = timelineFrameOf(scratch.range.start.value, scratch.range.start.timescale)
    val seeked = opens != next
    if (seeked) {
      ring.fill(0f)
      fed = opens
      current = -1
    }

    var input = buffers
    var read = wanted
    val reach = minOf((wanted + ahead).toLong(), unread - opens).toInt()
    if (source != null && reach > wanted) {
      input = source.armed(reach)
      if (!scratch.read(tap, reach, input)) return NO_SOURCE
      read = scratch.frames.value.toInt()
      val ended = scratch.flags.value and kMTAudioProcessingTapFlag_EndOfStream != 0u
      // MediaToolbox hands a tap no more than the block it was prepared for, so a kernel that holds back more than
      // that has no source to be fed from in time.
      check(read >= reach || ended) { tooFarAhead(ahead, read - wanted) }
    }
    feed(input, opens, read, until = opens + wanted + ahead)
    next = opens + wanted

    emit(buffers, opens, wanted)
    if (mixChain != null) mixed(buffers, wanted, opens, seeked)
    scratch.frames.value = wanted.toLong()
    scratch.flags.value = flags
    return wanted
  }

  // Runs every frame from where the kernels were last fed up to [until] through the span it sits in. [input] holds
  // [read] frames from the timeline frame [opens], and what lies past them is fed as silence.
  private fun feed(
    input: CPointer<AudioBufferList>,
    opens: Long,
    read: Int,
    until: Long,
  ) {
    var at = fed
    while (at < until) {
      val span = spanAt(at)
      val edge = if (span >= 0) ends[span] else nextStartAfter(at)
      val frames = (minOf(edge, until) - at).toInt()
      if (span >= 0) {
        if (span != current) {
          chains[span].reset(at)
          outAt[span] = at
        }
        load(input, (at - opens).toInt(), frames, read)
        made(span, chains[span].process(block, frames))
        if (at + frames == ends[span]) drain(span)
      }
      current = if (span >= 0 && at + frames == ends[span]) -1 else span
      at += frames
    }
    fed = until
  }

  // The frames the span's kernels still hold once its last frame has gone in.
  private fun drain(span: Int) {
    while (true) {
      val frames = chains[span].drain(block, capacity)
      if (frames == 0) return
      made(span, frames)
    }
  }

  // Applies the span's gain to the frames its kernels just wrote at the head of the block and puts them on the ring.
  private fun made(
    span: Int,
    frames: Int,
  ) {
    gains[span]?.apply(block, frames, channels, outAt[span] - starts[span])
    write(outAt[span], frames)
    outAt[span] += frames
  }

  // Runs the mix's effects over the [frames] frames the spans left in [buffers], which open at the timeline frame
  // [opens].
  private fun mixed(
    buffers: CPointer<AudioBufferList>,
    frames: Int,
    opens: Long,
    seeked: Boolean,
  ) {
    val chain = mixChain ?: return
    if (seeked) {
      chain.reset(opens)
      silentChain?.reset(opens)
    }
    load(buffers, 0, frames, frames)
    chain.process(block, frames)
    silentChain?.let { silent ->
      // What the effects write over silence is taken away again, on a track that leaves it out.
      silence.fill(0f, 0, frames * channels)
      silent.process(silence, frames)
      for (index in 0 until frames * channels) block[index] -= silence[index]
    }
    store(buffers, 0, frames)
  }

  private fun spanAt(frame: Long): Int {
    for (index in starts.indices) if (frame >= starts[index] && frame < ends[index]) return index
    return -1
  }

  private fun nextStartAfter(frame: Long): Long {
    for (index in starts.indices) if (starts[index] > frame) return starts[index]
    return Long.MAX_VALUE
  }

  private fun chainOf(
    effects: List<ResolvedAudioEffect>,
    start: Long,
  ): KernelChain =
    KernelChain(effects.map { it.effect.newKernel(format) }, channels, start, maxFrames, compensates = readsAhead)

  private fun gainsOf(spans: List<TapSpan>): Array<GainCurve?> =
    Array(spans.size) { index -> spans[index].gain?.let { GainCurve.of(it, format.sampleRate) } }

  private fun timelineFrameOf(
    value: Long,
    timescale: Int,
  ): Long {
    if (timescale <= 0) return 0
    val whole = value.floorDiv(timescale)
    val rest = (value - whole * timescale) * NANOS_PER_SECOND / timescale
    return timelineFrameOf(whole.seconds + rest.nanoseconds, format.sampleRate)
  }

  // Puts [frames] frames of [input] from [from] at the head of the block, with silence for any past the [held] it has.
  private fun load(
    input: CPointer<AudioBufferList>,
    from: Int,
    frames: Int,
    held: Int,
  ) {
    val real = (held - from).coerceIn(0, frames)
    val list = input.pointed
    if (planar) {
      for (channel in 0 until channels) {
        val data = list.mBuffers[channel].mData?.reinterpret<FloatVar>() ?: continue
        for (frame in 0 until real) block[frame * channels + channel] = data[from + frame]
      }
    } else {
      val data = list.mBuffers[0].mData?.reinterpret<FloatVar>() ?: return
      for (index in 0 until real * channels) block[index] = data[from * channels + index]
    }
    block.fill(0f, real * channels, frames * channels)
  }

  // Puts the first [frames] frames of the block on the ring from the timeline frame [at].
  private fun write(
    at: Long,
    frames: Int,
  ) {
    var slot = at.mod(capacity) * channels
    for (index in 0 until frames * channels) {
      ring[slot] = block[index]
      slot = if (slot + 1 == ring.size) 0 else slot + 1
    }
  }

  // Hands [buffers] what the ring holds of the [frames] frames from the timeline frame [opens], wherever a span
  // covers them, and clears it, so a frame the kernels have not made plays as silence. Audio outside every span stays
  // as it was read.
  private fun emit(
    buffers: CPointer<AudioBufferList>,
    opens: Long,
    frames: Int,
  ) {
    val until = opens + frames
    var at = opens
    while (at < until) {
      val span = spanAt(at)
      val edge = if (span >= 0) ends[span] else nextStartAfter(at)
      val count = (minOf(edge, until) - at).toInt()
      if (span >= 0) {
        var slot = at.mod(capacity) * channels
        for (index in 0 until count * channels) {
          block[index] = ring[slot]
          ring[slot] = 0f
          slot = if (slot + 1 == ring.size) 0 else slot + 1
        }
        store(buffers, (at - opens).toInt(), count)
      }
      at += count
    }
  }

  private fun store(
    buffers: CPointer<AudioBufferList>,
    at: Int,
    frames: Int,
  ) {
    val list = buffers.pointed
    if (planar) {
      for (channel in 0 until channels) {
        val data = list.mBuffers[channel].mData?.reinterpret<FloatVar>() ?: continue
        for (frame in 0 until frames) data[at + frame] = block[frame * channels + channel]
      }
    } else {
      val data = list.mBuffers[0].mData?.reinterpret<FloatVar>() ?: return
      for (index in 0 until frames * channels) data[at * channels + index] = block[index]
    }
  }

  companion object {
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val NO_SOURCE = -1

    /**
     * The stage for [format], or null when it is not the 32-bit float PCM a tap is documented to process in, in which
     * case the track's audio passes through.
     */
    fun of(
      spans: List<TapSpan>,
      mix: TapMix?,
      maxFrames: Int,
      format: AudioStreamBasicDescription,
      readsUntil: Duration?,
    ): TapStage? {
      val float = format.mFormatFlags and kAudioFormatFlagIsFloat != 0u
      if (format.mFormatID != kAudioFormatLinearPCM || !float || format.mBitsPerChannel != FLOAT_BITS) return null
      return TapStage(
        format = AudioFormat(format.mSampleRate.toInt(), format.mChannelsPerFrame.toInt()),
        planar = format.mFormatFlags and kAudioFormatFlagIsNonInterleaved != 0u,
        maxFrames = maxFrames,
        readsUntil = readsUntil,
        spans = spans,
        mix = mix,
      )
    }

    private fun tooFarAhead(
      latency: Int,
      handed: Int,
    ): String =
      "the effects hold back $latency frames and AVFoundation hands an export $handed ahead of a block. " +
        "Lower the kernels' latencyFrames."

    private const val FLOAT_BITS = 32u
    private val EMPTY = FloatArray(0)
  }
}

/**
 * Reads the source audio of the next [frames] frames into [buffers], leaving how many came, their flags and their
 * time range in this scratch.
 *
 * @return false when AVFoundation handed over nothing.
 */
internal fun TapScratch.read(
  tap: MTAudioProcessingTapRef?,
  frames: Int,
  buffers: CPointer<AudioBufferList>,
): Boolean =
  MTAudioProcessingTapGetSourceAudio(tap, frames.toLong(), buffers, flags.ptr, range.ptr, this.frames.ptr) == 0

// A buffer list of a tap's own, in the layout the tap processes in, for source audio longer than the block the tap
// was handed buffers for.
private class TapSource(
  planar: Boolean,
  channels: Int,
  private val frames: Int,
) {
  private val buffers = if (planar) channels else 1
  private val perBuffer = if (planar) 1 else channels
  private val list: AudioBufferList =
    nativeHeap
      .alloc(sizeOf<AudioBufferList>() + (buffers - 1) * sizeOf<AudioBuffer>(), alignOf<AudioBufferList>())
      .reinterpret()

  init {
    list.mNumberBuffers = buffers.toUInt()
    for (index in 0 until buffers) {
      list.mBuffers[index].mNumberChannels = perBuffer.toUInt()
      list.mBuffers[index].mData = nativeHeap.allocArray<FloatVar>(frames * perBuffer)
    }
  }

  // The list with room for [wanted] frames, which a read shortens to what it was handed.
  fun armed(wanted: Int): CPointer<AudioBufferList> {
    val bytes = (minOf(wanted, frames) * perBuffer * Float.SIZE_BYTES).toUInt()
    for (index in 0 until buffers) list.mBuffers[index].mDataByteSize = bytes
    return list.ptr
  }

  fun free() {
    for (index in 0 until buffers) list.mBuffers[index].mData?.let { nativeHeap.free(it.rawValue) }
    nativeHeap.free(list.rawPtr)
  }
}
