@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.transform.internal.audio.KernelChain
import dev.jordond.filmstrip.transform.internal.audio.timelineFrameOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsNonInterleaved
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreMedia.CMItemCount
import platform.CoreMedia.CMItemCountVar
import platform.CoreMedia.CMTimeRange
import platform.MediaToolbox.MTAudioProcessingTapCallbacks
import platform.MediaToolbox.MTAudioProcessingTapCreate
import platform.MediaToolbox.MTAudioProcessingTapFlags
import platform.MediaToolbox.MTAudioProcessingTapFlagsVar
import platform.MediaToolbox.MTAudioProcessingTapGetSourceAudio
import platform.MediaToolbox.MTAudioProcessingTapGetStorage
import platform.MediaToolbox.MTAudioProcessingTapRef
import platform.MediaToolbox.MTAudioProcessingTapRefVar
import platform.MediaToolbox.kMTAudioProcessingTapCallbacksVersion_0
import platform.MediaToolbox.kMTAudioProcessingTapCreationFlag_PreEffects
import platform.MediaToolbox.kMTAudioProcessingTapFlag_EndOfStream
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The audio effects one stretch of a track runs, and where on the composition timeline it runs them.
 *
 * @property range The composition time the effects cover.
 * @property effects The effects to run over the track's audio inside [range], first to last.
 * @property gain The level the tap applies to what the effects wrote, measured from the start of [range], or null
 * when the track's volume ramps apply it after the tap.
 */
internal class TapSpan(
  val range: TimeRange,
  val effects: List<ResolvedAudioEffect>,
  val gain: ResolvedGain? = null,
)

/**
 * The composition's audio effects as one track's tap runs them, over everything the track plays and after its spans.
 *
 * A player mixes its tracks after their taps, so each track runs the effects over its own audio. The sound the
 * effects make of their own is what they write over silence, and a track that does not carry it takes that away
 * again, so the mix hears it once.
 *
 * @property effects The effects to run, first to last.
 * @property carriesAdditive Whether this track keeps the sound the effects make of their own.
 */
internal class TapMix(
  val effects: List<ResolvedAudioEffect>,
  val carriesAdditive: Boolean,
)

/**
 * What an [MTAudioProcessingTapRef] built by [kernelTapOf] runs, which a caller keeps to change it while it plays.
 *
 * The tap sits ahead of the track's volume ramps, so a clip's level is applied to what its effects wrote. Each span
 * runs its effects through a [KernelChain] of its own, at the format the track is processed in, and audio outside
 * every span passes through untouched. A kernel is handed the timeline position AVFoundation reports for the block,
 * through [timelineFrameOf]. A span with a gain has it applied here, and [mix] then runs over the whole block.
 *
 * A tap is built for a player, which hands it no source ahead of a block. That is AVFoundation's limit, and the
 * track plays late by what its kernels hold back.
 *
 * @param mix The composition's effects to run after the spans, or null when there are none.
 */
internal class AvKernelTap(
  spans: List<TapSpan>,
  mix: TapMix? = null,
) {
  @Volatile
  private var spans: List<TapSpan> = spans.sortedBy { it.range.start }

  @Volatile
  private var mix: TapMix? = mix

  /**
   * Called on the audio thread the first time a kernel throws, with what [failure] then holds.
   */
  @Volatile
  var onFailure: ((FilmstripError) -> Unit)? = null

  @Volatile
  private var stage: TapStage? = null

  /**
   * How many blocks the tap has been handed, which a test reads to tell that it ran.
   */
  @Volatile
  var blocks: Long = 0
    private set

  /**
   * What stopped the effects running, or null while they run. The track's audio passes through untouched from the
   * block a kernel threw on.
   */
  @Volatile
  var failure: FilmstripError? = null
    private set

  /**
   * Runs [spans] and [mix] in place of the standing ones, from the next block on.
   *
   * A span covering the same range as a standing one keeps its place on the timeline and swaps its kernels and its
   * gain in, and the mix swaps its kernels in. Any other change takes effect the next time AVFoundation prepares
   * the tap.
   */
  fun swap(
    spans: List<TapSpan>,
    mix: TapMix? = this.mix,
  ) {
    val sorted = spans.sortedBy { it.range.start }
    this.spans = sorted
    this.mix = mix
    stage?.swap(sorted, mix)
  }

  internal fun prepare(
    maxFrames: Int,
    format: AudioStreamBasicDescription,
  ) {
    stage = TapStage.of(spans, mix, maxFrames, format)
  }

  internal fun unprepare() {
    stage = null
  }

  internal fun process(
    tap: MTAudioProcessingTapRef?,
    frames: CMItemCount,
    buffers: CPointer<AudioBufferList>?,
    framesOut: CPointer<CMItemCountVar>?,
    flagsOut: CPointer<MTAudioProcessingTapFlagsVar>?,
    scratch: TapScratch,
  ) {
    blocks++
    if (buffers == null) {
      framesOut?.pointed?.value = 0
      return
    }
    val running = stage.takeIf { failure == null }
    val written =
      try {
        running?.run(tap, frames.toInt(), buffers, scratch) ?: passedThrough(tap, frames.toInt(), buffers, scratch)
      } catch (broken: Exception) {
        // An exception leaving the callback would terminate the process, so the track plays on without its effects.
        val error = FilmstripError.InvalidComposition("$CLIP_EFFECTS_NOT_RUN ${broken.message ?: broken.toString()}")
        failure = error
        onFailure?.invoke(error)
        passedThrough(tap, frames.toInt(), buffers, scratch)
      }
    framesOut?.pointed?.value = maxOf(written, 0).toLong()
    flagsOut?.pointed?.value = scratch.flags.value
  }

  // The source of the block as AVFoundation hands it over, with nothing run on it.
  private fun passedThrough(
    tap: MTAudioProcessingTapRef?,
    frames: Int,
    buffers: CPointer<AudioBufferList>,
    scratch: TapScratch,
  ): Int = if (scratch.read(tap, frames, buffers)) scratch.frames.value.toInt() else 0
}

/**
 * Builds the tap that runs [tap] on one track of an audio mix.
 *
 * The caller owns the reference it is handed and releases it once the mix parameters hold it.
 *
 * @return the tap, or null when MediaToolbox refuses to build one.
 */
internal fun kernelTapOf(tap: AvKernelTap): MTAudioProcessingTapRef? =
  memScoped {
    val callbacks =
      alloc<MTAudioProcessingTapCallbacks>().apply {
        version = kMTAudioProcessingTapCallbacksVersion_0
        clientInfo = StableRef.create(TapStorage(tap)).asCPointer()
        `init` = staticCFunction(::tapInit)
        finalize = staticCFunction(::tapFinalize)
        prepare = staticCFunction(::tapPrepare)
        unprepare = staticCFunction(::tapUnprepare)
        process = staticCFunction(::tapProcess)
      }
    val out = alloc<MTAudioProcessingTapRefVar>()
    val status =
      MTAudioProcessingTapCreate(
        kCFAllocatorDefault,
        callbacks.ptr,
        kMTAudioProcessingTapCreationFlag_PreEffects,
        out.ptr,
      )
    if (status != 0) {
      callbacks.clientInfo?.asStableRef<TapStorage>()?.dispose()
      return null
    }
    out.value
  }

// What a tap's storage pointer holds for the life of the tap: the kernels and the native values the process callback
// reads its source audio into, built once so that callback allocates nothing.
private class TapStorage(
  val tap: AvKernelTap,
) {
  val scratch = TapScratch()
}

/**
 * The native values one tap reads each block's source audio into.
 */
internal class TapScratch {
  val flags: UIntVar = nativeHeap.alloc()
  val range: CMTimeRange = nativeHeap.alloc()
  val frames: CMItemCountVar = nativeHeap.alloc()

  fun free() {
    nativeHeap.free(flags)
    nativeHeap.free(range)
    nativeHeap.free(frames)
  }
}

private fun storageOf(tap: MTAudioProcessingTapRef?): TapStorage? =
  MTAudioProcessingTapGetStorage(tap)?.asStableRef<TapStorage>()?.get()

private fun tapInit(
  @Suppress("UNUSED_PARAMETER") tap: MTAudioProcessingTapRef?,
  clientInfo: COpaquePointer?,
  storageOut: CPointer<COpaquePointerVar>?,
) {
  storageOut?.pointed?.value = clientInfo
}

private fun tapFinalize(tap: MTAudioProcessingTapRef?) {
  val reference = MTAudioProcessingTapGetStorage(tap)?.asStableRef<TapStorage>() ?: return
  reference.get().scratch.free()
  reference.dispose()
}

private fun tapPrepare(
  tap: MTAudioProcessingTapRef?,
  maxFrames: CMItemCount,
  format: CPointer<AudioStreamBasicDescription>?,
) {
  val description = format?.pointed ?: return
  storageOf(tap)?.tap?.prepare(maxFrames.toInt(), description)
}

private fun tapUnprepare(tap: MTAudioProcessingTapRef?) {
  storageOf(tap)?.tap?.unprepare()
}

private fun tapProcess(
  tap: MTAudioProcessingTapRef?,
  frames: CMItemCount,
  @Suppress("UNUSED_PARAMETER") flags: MTAudioProcessingTapFlags,
  buffers: CPointer<AudioBufferList>?,
  framesOut: CPointer<CMItemCountVar>?,
  flagsOut: CPointer<MTAudioProcessingTapFlagsVar>?,
) {
  val storage = storageOf(tap) ?: return
  storage.tap.process(tap, frames, buffers, framesOut, flagsOut, storage.scratch)
}

/**
 * What an error opens with when a clip's audio effect threw, ahead of what it threw with.
 */
internal const val CLIP_EFFECTS_NOT_RUN: String = "A clip's audio effects could not run."
