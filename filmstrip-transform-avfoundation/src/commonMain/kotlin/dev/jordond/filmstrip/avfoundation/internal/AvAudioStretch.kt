package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
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
 * Why one export could not render a clip's audio at its speed.
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
 */
internal class ClipAudioRender(
  val clip: ResolvedClip,
  val track: AVAssetTrack,
  val path: String,
  val sampleRate: Int,
  val channels: Int,
) {
  /**
   * How many frames the file holds, the shared count for the clip's [ResolvedClip.outputDuration].
   */
  val frames: Long get() = audioFramesOf(clip.outputDuration, sampleRate)
}

/**
 * The audio of every clip at a speed other than 1, rendered to files for one export to lay at speed 1.
 *
 * Files land in a directory of their own under the root named at construction, which [discard] removes with everything
 * in it. The run owns an instance from before the first render until after the last read.
 */
@OptIn(ExperimentalForeignApi::class)
internal class StretchedAudio(
  root: String,
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
   * Renders the audio of every clip [resolved] lays at a speed other than 1, once per laid clip.
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
          val track = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
          track?.let {
            assets += asset
            ClipAudioRender(clip, it, "$directory/$index.caf", format.sampleRate, format.channelCount)
          }
        }
    if (renders.isEmpty()) return

    sweepAbandoned()
    prepareDirectory(renders.sumOf { it.frames * it.channels * BYTES_PER_SAMPLE })
    renders.forEach { each ->
      render(each)
      rendered[each.clip] = openRendered(each)
    }
  }

  private fun ResolvedComposition.clipsToRender(): List<ResolvedClip> =
    tracks
      .filter { it.content != TrackContent.Video }
      .flatMap { it.clips }
      .filter { it.speed != 1.0 && it.source !is MediaSource.Image }

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
          "A clip's audio could not be stretched because $directory could not be created.",
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
          "Stretching this export's audio needs about $requiredBytes bytes and $free are free.",
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
          "The stretched audio of ${render.clip.source.describe()} could not be read back.",
        ),
      )
  }

  companion object {
    /**
     * What every directory of stretched audio is named from.
     */
    const val DIRECTORY_PREFIX: String = "filmstrip-stretch-"

    private const val ABANDONED_AFTER_SECONDS = 24.0 * 60.0 * 60.0
  }
}

/**
 * The settings a clip's source audio is decoded with on its way to the time pitch unit.
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
 * Renders [render]'s clip through `AVAudioEngine` manual rendering and `AVAudioUnitTimePitch` into its file.
 *
 * The rate is the clip's speed and the pitch stays where the source has it. The source is read through an
 * `AVAssetReader` one decoded buffer at a time, as the engine asks for it, and the output goes to disk in fixed blocks,
 * so memory use does not grow with the clip. The file holds exactly [ClipAudioRender.frames] frames. The time pitch
 * unit carries on flushing its window past the end of its input and runs on in silence after that, and all of it past
 * that count is left out.
 *
 * Cancelling stops the render between blocks and stops a read in progress, and leaves the partial file for
 * [StretchedAudio.discard].
 *
 * @throws AudioStretchFailure When the source could not be read or the engine or the file refused a block.
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
    val engine = AVAudioEngine()
    var file: StretchFile? = null
    try {
      val node = AVAudioSourceNode(format) { silence, _, frames, list -> feed.fill(silence, frames, list) }
      val timePitch = AVAudioUnitTimePitch().apply { rate = render.clip.speed.toFloat() }
      engine.attachNode(node)
      engine.attachNode(timePitch)
      engine.connect(node, to = timePitch, format = format)
      engine.connect(timePitch, to = engine.mainMixerNode, format = format)
      memScoped {
        val failure = alloc<ObjCObjectVar<NSError?>>()
        val started =
          engine.enableManualRenderingMode(
            AVAudioEngineManualRenderingModeOffline,
            format,
            RENDER_BLOCK_FRAMES,
            failure.ptr,
          ) &&
            engine.startAndReturnError(failure.ptr)
        if (!started) throw AudioStretchFailure(refused(source, failure.value?.code?.toInt()))
      }
      val block = AVAudioPCMBuffer(pCMFormat = engine.manualRenderingFormat, frameCapacity = RENDER_BLOCK_FRAMES)
      file = StretchFile(render.path, format, render.channels, source)

      var written = 0L
      while (written < render.frames) {
        ensureActive()
        val want = minOf(RENDER_BLOCK_FRAMES.toLong(), render.frames - written).toUInt()
        memScoped {
          val failure = alloc<ObjCObjectVar<NSError?>>()
          val status = engine.renderOffline(want, toBuffer = block, error = failure.ptr)
          if (status != AVAudioEngineManualRenderingStatusSuccess || block.frameLength == 0u) {
            throw AudioStretchFailure(refused(source, failure.value?.code?.toInt() ?: status.toInt()))
          }
        }
        file.write(block)
        written += block.frameLength.toLong()
        feed.failedStatus?.let { throw AudioStretchFailure(refused(source, it)) }
        if (reader.status == AVAssetReaderStatusFailed) unreadable(source)
      }
    } finally {
      // The one call to cancelReading, since two at once tear the reader's outputs down twice.
      withContext(NonCancellable) { stopsReading.cancelAndJoin() }
      engine.stop()
      file?.close()
    }
  }

private fun unreadable(source: String): Nothing =
  throw AudioStretchFailure(
    FilmstripError.SourceUnreadable(source, "The audio of $source could not be read to stretch it to its speed."),
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
