package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.audio.KernelChain
import dev.jordond.filmstrip.transform.internal.audio.timelineFrameOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineManualRenderingModeOffline
import platform.AVFAudio.AVAudioEngineManualRenderingStatusSuccess
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSourceNode
import platform.AVFAudio.AVAudioUnitTimePitch
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleavedKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.timeIntervalSinceDate

/**
 * Why a clip's audio could not be rendered to a file.
 *
 * @property error What the export fails with.
 */
internal class AudioStretchFailure(
  val error: FilmstripError,
) : Exception(error.message)

/**
 * One laid clip whose audio is rendered to a file of its own.
 *
 * @property track The audio track of the clip's source.
 * @property path Where the file lands.
 * @property sampleRate The rate the file runs at, which is the output's.
 * @property channels How many channels the file holds, which is the output's.
 * @property effects The audio effects run over the clip's audio on its way into the file, first to last. Empty when
 * the file holds the clip's audio as its source has it.
 */
internal class ClipAudioRender(
  val clip: ResolvedClip,
  val track: AVAssetTrack,
  val path: String,
  val sampleRate: Int,
  val channels: Int,
  val effects: List<ResolvedAudioEffect> = emptyList(),
) {
  /**
   * How many frames the file holds, the shared count for the clip's [ResolvedClip.outputDuration].
   */
  val frames: Long get() = audioFramesOf(clip.outputDuration, sampleRate)

  /**
   * Whether the file holds float samples, which is what keeps a sample an effect wrote past full scale. A file with
   * no effect in it holds 16-bit samples.
   */
  val float: Boolean get() = effects.isNotEmpty()

  /**
   * The bytes one sample of the file takes.
   */
  val bytesPerSample: Int get() = if (float) FLOAT_BYTES_PER_SAMPLE else INT16_BYTES_PER_SAMPLE
}

/**
 * The audio of every clip that is laid from a file of its own, rendered ahead of the composition and laid at speed 1.
 *
 * A clip at a speed other than 1 is rendered at that speed. With [bakesEffects] a clip with audio effects is rendered
 * too, at any speed, with the effects already run over it at the clip's place on the timeline. Every pass of a looping
 * track is a clip of its own here and gets a file of its own.
 *
 * Files land in a directory of their own under the root named at construction, which [discard] removes with everything
 * in it. The run owns an instance from before the first render until after the last read.
 *
 * @param bakesEffects Whether a clip's audio effects are run into its file. True for an export, which then puts no
 * tap under its reader. False for a preview, whose taps run them.
 */
@OptIn(ExperimentalForeignApi::class)
internal class StretchedAudio(
  root: String,
  private val bakesEffects: Boolean = false,
  private val render: suspend (ClipAudioRender) -> Unit = ::renderClipAudio,
) {
  private val root = root.trimEnd('/')
  private val directory = "${this.root}/$DIRECTORY_PREFIX${NSUUID().UUIDString}"
  private val rendered = mutableMapOf<ResolvedClip, AVAssetTrack>()

  // An asset track only lasts as long as the asset it came from.
  private val assets = mutableListOf<AVURLAsset>()

  /**
   * The file [clip] lays instead of its source's audio, or null when it lays its source.
   */
  fun trackOf(clip: ResolvedClip): AVAssetTrack? = rendered[clip]

  /**
   * Whether [resolved] lays a clip this instance may have to render, which is what makes an export start before it has
   * written a frame.
   */
  fun mayRender(resolved: ResolvedComposition): Boolean =
    resolved.output.audioFormat != null &&
      resolved.output.audioCodec != AudioCodec.None &&
      resolved.clipsToRender().isNotEmpty()

  /**
   * Renders the audio of every clip of [resolved] that is laid from a file, once per laid clip.
   *
   * A clip whose source has no audio has nothing to render and takes its empty time as usual.
   *
   * @param bytesRoot The directory an in-memory source lands under.
   * @throws AudioStretchFailure When a clip's audio could not be read, rendered or written.
   */
  suspend fun renderAll(
    resolved: ResolvedComposition,
    bytesRoot: String,
  ) {
    val format = resolved.output.audioFormat
    if (format == null || resolved.output.audioCodec == AudioCodec.None) return

    val renders =
      resolved
        .clipsToRender()
        .mapIndexedNotNull { index, clip ->
          val asset = AVURLAsset(uRL = clip.source.toNSURL(bytesRoot), options = null)
          asset.audioTrackOf(clip)?.let {
            assets += asset
            val effects = if (bakesEffects) clip.audioEffects else emptyList()
            ClipAudioRender(clip, it, "$directory/$index.caf", format.sampleRate, format.channelCount, effects)
          }
        }
    if (renders.isEmpty()) return

    sweepAbandoned()
    prepareDirectory(renders.sumOf { it.frames * it.channels * it.bytesPerSample })
    renders.forEach { each ->
      render(each)
      rendered[each.clip] = openRendered(each)
    }
  }

  private fun ResolvedComposition.clipsToRender(): List<ResolvedClip> =
    tracks
      .filter { it.content != TrackContent.Video }
      .flatMap { it.clips }
      .filter { it.source !is MediaSource.Image }
      .filter { it.speed != 1.0 || (bakesEffects && it.audioEffects.isNotEmpty()) }

  /**
   * Removes every file this instance rendered. Safe to call when nothing was.
   */
  fun discard() {
    rendered.clear()
    assets.clear()
    NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
  }

  // A killed process never reaches its discard. Anything newer than the cutoff may belong to an export still running.
  private fun sweepAbandoned() {
    val manager = NSFileManager.defaultManager
    val now = NSDate()
    manager
      .contentsOfDirectoryAtPath(root, error = null)
      .orEmpty()
      .filterIsInstance<String>()
      .filter { it.startsWith(DIRECTORY_PREFIX) }
      .forEach { name ->
        val path = "$root/$name"
        val modified = manager.attributesOfItemAtPath(path, error = null)?.get(NSFileModificationDate) as? NSDate
        if (modified != null && now.timeIntervalSinceDate(modified) > ABANDONED_AFTER_SECONDS) {
          manager.removeItemAtPath(path, error = null)
        }
      }
  }

  private fun prepareDirectory(requiredBytes: Long) {
    val manager = NSFileManager.defaultManager
    val created =
      manager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
    if (!created) {
      throw AudioStretchFailure(
        FilmstripError.Underlying(
          FilmstripError.Underlying.NO_PLATFORM_CODE,
          "A clip's audio could not be rendered because $directory could not be created.",
        ),
      )
    }
    val free =
      (manager.attributesOfFileSystemForPath(directory, error = null)?.get(NSFileSystemFreeSize) as? NSNumber)
        ?.longLongValue ?: return
    if (free < requiredBytes) {
      throw AudioStretchFailure(
        FilmstripError.InsufficientStorage(
          requiredBytes,
          "Rendering this export's audio needs about $requiredBytes bytes and $free are free.",
        ),
      )
    }
  }

  private fun openRendered(render: ClipAudioRender): AVAssetTrack {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(render.path), options = null)
    assets += asset
    return asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
      ?: throw AudioStretchFailure(
        FilmstripError.Underlying(
          FilmstripError.Underlying.NO_PLATFORM_CODE,
          "The rendered audio of ${render.clip.source.describe()} could not be read back.",
        ),
      )
  }

  companion object {
    /**
     * What every directory of rendered audio is named from.
     */
    const val DIRECTORY_PREFIX: String = "filmstrip-stretch-"

    private const val ABANDONED_AFTER_SECONDS = 24.0 * 60.0 * 60.0
  }
}

/**
 * The settings a clip's source audio is decoded with on its way into its file.
 *
 * Non-interleaved float at the output's rate, which is the layout [AVAudioFormat]'s standard format holds, so a sample
 * buffer copies straight across.
 */
internal fun stretchReaderSettings(
  sampleRate: Int,
  channels: Int,
): Map<Any?, Any?> =
  mapOf(
    AVFormatIDKey to kAudioFormatLinearPCM,
    AVSampleRateKey to sampleRate.toDouble(),
    AVNumberOfChannelsKey to channels,
    AVLinearPCMBitDepthKey to FLOAT_BIT_DEPTH,
    AVLinearPCMIsFloatKey to true,
    AVLinearPCMIsBigEndianKey to false,
    AVLinearPCMIsNonInterleavedKey to true,
  )

/**
 * Renders [render]'s clip into its file, at the clip's speed and with [ClipAudioRender.effects] run over it.
 *
 * The source is read through an `AVAssetReader` one decoded buffer at a time and the output goes to disk in fixed
 * blocks, so memory use does not grow with the clip. A clip at a speed other than 1 goes through `AVAudioEngine`
 * manual rendering and `AVAudioUnitTimePitch`, with the clip's speed as the rate and the pitch left where the source
 * has it. A clip at speed 1 is read straight from the decoder.
 *
 * The effects then run in a [KernelChain] that starts on the timeline frame the clip is laid at, so a kernel is handed
 * the composition's own positions. The chain takes the kernels' latency out of the head and what they still hold is
 * drained at the end, which keeps the clip's audio on its frames.
 *
 * The file holds exactly [ClipAudioRender.frames] frames. A source that ends before that is padded with silence, which
 * the effects still run over. The time pitch unit carries on flushing its window past the end of its input and runs
 * on in silence after that, and all of it past that count is left out.
 *
 * Cancelling stops the render between blocks and stops a read in progress, and leaves the partial file for
 * [StretchedAudio.discard].
 *
 * @throws AudioStretchFailure When the source could not be read, the engine or the file refused a block, or an effect
 * threw.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal suspend fun renderClipAudio(render: ClipAudioRender): Unit =
  withContext(Dispatchers.IO) {
    val source = render.clip.source.describe()
    val decode = AVAssetReaderTrackOutput(render.track, stretchReaderSettings(render.sampleRate, render.channels))
    val reader =
      AVAssetReader.assetReaderWithAsset(render.track.asset ?: unreadable(source), error = null) ?: unreadable(source)
    reader.timeRange = CMTimeRangeMake(render.clip.start.toCMTime(), render.clip.sourceDuration.toCMTime())
    reader.addOutput(decode)
    if (!reader.startReading()) unreadable(source)

    val stopsReading =
      launch {
        try {
          awaitCancellation()
        } finally {
          reader.cancelReading()
        }
      }
    val format =
      AVAudioFormat(standardFormatWithSampleRate = render.sampleRate.toDouble(), channels = render.channels.convert())
    val feed = DecodedFeed(decode, format)
    val engine = if (render.clip.speed == 1.0) null else AVAudioEngine()
    var file: StretchFile? = null
    try {
      engine?.stretches(feed, format, render.clip.speed, source)
      val effects = if (render.effects.isEmpty()) null else BakedEffects(render)
      val block = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = RENDER_BLOCK_FRAMES)
      file = StretchFile(render.path, format, render.channels, render.float, source)

      var read = 0L
      while (read < render.frames) {
        ensureActive()
        val want = minOf(RENDER_BLOCK_FRAMES.toLong(), render.frames - read).toUInt()
        if (engine == null) {
          block.frameLength = want
          feed.fill(null, want, block.mutableAudioBufferList)
        } else {
          engine.renderBlock(want, block, source)
        }
        read += block.frameLength.toLong()
        effects?.process(block)
        if (block.frameLength > 0u) file.write(block)
        feed.failedStatus?.let { throw AudioStretchFailure(refused(source, it)) }
        if (reader.status == AVAssetReaderStatusFailed) unreadable(source)
      }
      while (effects?.drain(block) == true) {
        ensureActive()
        file.write(block)
      }
    } finally {
      // The one call to cancelReading, since two at once tear the reader's outputs down twice.
      withContext(NonCancellable) { stopsReading.cancelAndJoin() }
      engine?.stop()
      file?.close()
    }
  }

// Starts this engine rendering what the feed hands it through a time pitch unit at the clip's speed.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun AVAudioEngine.stretches(
  feed: DecodedFeed,
  format: AVAudioFormat,
  speed: Double,
  source: String,
) {
  val node = AVAudioSourceNode(format) { silence, _, frames, list -> feed.fill(silence, frames, list) }
  val timePitch = AVAudioUnitTimePitch().apply { rate = speed.toFloat() }
  attachNode(node)
  attachNode(timePitch)
  connect(node, to = timePitch, format = format)
  connect(timePitch, to = mainMixerNode, format = format)
  memScoped {
    val failure = alloc<ObjCObjectVar<NSError?>>()
    val started =
      enableManualRenderingMode(AVAudioEngineManualRenderingModeOffline, format, RENDER_BLOCK_FRAMES, failure.ptr) &&
        startAndReturnError(failure.ptr)
    if (!started) throw AudioStretchFailure(refused(source, failure.value?.code?.toInt()))
  }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun AVAudioEngine.renderBlock(
  frames: UInt,
  block: AVAudioPCMBuffer,
  source: String,
) {
  memScoped {
    val failure = alloc<ObjCObjectVar<NSError?>>()
    val status = renderOffline(frames, toBuffer = block, error = failure.ptr)
    if (status != AVAudioEngineManualRenderingStatusSuccess || block.frameLength == 0u) {
      throw AudioStretchFailure(refused(source, failure.value?.code?.toInt() ?: status.toInt()))
    }
  }
}

/**
 * Runs a clip's audio effects over the blocks on their way into its file.
 *
 * The kernels work at the file's rate and channel count, from the timeline frame the clip is laid at.
 *
 * @throws AudioStretchFailure From any call, when a kernel threw.
 */
@OptIn(ExperimentalForeignApi::class)
private class BakedEffects(
  render: ClipAudioRender,
) {
  private val channels = render.channels
  private val samples = FloatArray(RENDER_BLOCK_FRAMES.toInt() * channels)
  private val chain =
    running {
      val format = AudioFormat(render.sampleRate, channels)
      KernelChain(
        kernels = render.effects.map { it.effect.newKernel(format) },
        channels = channels,
        start = timelineFrameOf(render.clip.span.start, render.sampleRate),
        maxFrames = RENDER_BLOCK_FRAMES.toInt(),
      )
    }

  /**
   * Runs the effects over [block] in place. The block comes back shorter, or empty, while the kernels hold frames
   * back at the head of the clip.
   */
  fun process(block: AVAudioPCMBuffer) {
    val frames = block.frameLength.toInt()
    val planes = block.floatChannelData ?: return
    for (channel in 0 until channels) {
      val plane = planes[channel] ?: continue
      for (frame in 0 until frames) samples[frame * channels + channel] = plane[frame]
    }
    store(block, running { chain.process(samples, frames) })
  }

  /**
   * Fills [block] with the next frames the kernels still hold once the clip's last frame has gone in.
   *
   * @return false when nothing is left, with [block] empty.
   */
  fun drain(block: AVAudioPCMBuffer): Boolean {
    val frames = running { chain.drain(samples, RENDER_BLOCK_FRAMES.toInt()) }
    store(block, frames)
    return frames > 0
  }

  private fun store(
    block: AVAudioPCMBuffer,
    frames: Int,
  ) {
    block.frameLength = frames.toUInt()
    val planes = block.floatChannelData ?: return
    for (channel in 0 until channels) {
      val plane = planes[channel] ?: continue
      for (frame in 0 until frames) plane[frame] = samples[frame * channels + channel]
    }
  }

  private inline fun <T> running(work: () -> T): T =
    try {
      work()
    } catch (broken: Exception) {
      val reason = "$CLIP_EFFECTS_NOT_RUN ${broken.message ?: broken.toString()}"
      throw AudioStretchFailure(FilmstripError.InvalidComposition(reason))
    }
}

private fun unreadable(source: String): Nothing =
  throw AudioStretchFailure(
    FilmstripError.SourceUnreadable(source, "The audio of $source could not be read to render it."),
  )

internal fun refused(
  source: String,
  code: Int?,
): FilmstripError =
  FilmstripError.Underlying(
    code ?: FilmstripError.Underlying.NO_PLATFORM_CODE,
    "AVAudioEngine could not stretch the audio of $source to its speed.",
  )

private const val FLOAT_BIT_DEPTH = 32
