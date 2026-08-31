@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * What a source's video track turned out to be, read from the container without decoding a frame.
 *
 * @property frameRate The track's measured rate, from a bounded packet walk. Null when the track
 *   holds too few packets to measure one.
 */
internal class SourceVideoInfo(
  val durationUs: Double,
  val width: Int,
  val height: Int,
  val rotationDegrees: Int,
  val codec: String,
  val frameRate: Float?,
)

/**
 * One source, opened once and read many times.
 *
 * The same reader answers the plan's metadata questions and streams the export's frames, so a
 * source is parsed once per export rather than once per question.
 */
internal class SourceReader(
  private val input: Input,
) {
  private var track: InputVideoTrack? = null
  private var looked = false
  private var audio: InputAudioTrack? = null
  private var lookedAudio = false

  suspend fun videoTrack(): InputVideoTrack? {
    if (!looked) {
      track = input.getPrimaryVideoTrack().await()
      looked = true
    }
    return track
  }

  suspend fun audioTrack(): InputAudioTrack? {
    if (!lookedAudio) {
      audio = input.getPrimaryAudioTrack().await()
      lookedAudio = true
    }
    return audio
  }

  /**
   * The file's duration, in microseconds. The largest end timestamp among all its tracks, so a
   * source with audio running past its video reads its real length rather than the video's.
   */
  suspend fun durationUs(): Double = input.computeDuration().await().toDouble() * MICROS_PER_SECOND

  suspend fun videoInfo(): SourceVideoInfo? {
    val track = videoTrack() ?: return null
    return SourceVideoInfo(
      durationUs = track.computeDuration().await().toDouble() * MICROS_PER_SECOND,
      width =
        track
          .getDisplayWidth()
          .await()
          .toDouble()
          .toInt(),
      height =
        track
          .getDisplayHeight()
          .await()
          .toDouble()
          .toInt(),
      rotationDegrees =
        track
          .getRotation()
          .await()
          .toDouble()
          .toInt(),
      codec = track.getCodec().await()?.toString() ?: return null,
      frameRate = measuredFrameRate(track),
    )
  }

  /**
   * Decoded frames covering `[startUs, endUs)`. An [endUs] that is not finite runs to the end of
   * the track.
   */
  suspend fun frames(
    startUs: Double,
    endUs: Double,
  ): FrameStream? {
    val track = videoTrack() ?: return null
    val sink = VideoSampleSink(track)
    val end = if (endUs.isFinite()) endUs / MICROS_PER_SECOND else OPEN_ENDED_SECONDS
    return FrameStream(sink.samples(startUs / MICROS_PER_SECOND, end))
  }

  /**
   * A sampler over this source's video track, for a caller that reads frames out of order.
   *
   * Each call opens a decoder of its own, so a preview can read a frame back without disturbing the
   * one playback is decoding through.
   */
  suspend fun sampler(): FrameSampler? {
    val track = videoTrack() ?: return null
    return FrameSampler(VideoSampleSink(track))
  }

  /**
   * The timestamp of the sync sample at or before [timestampUs], or null when the track carries
   * none before it.
   */
  suspend fun keyFrameAt(timestampUs: Double): Double? {
    val track = videoTrack() ?: return null
    val packet = EncodedPacketSink(track).getKeyPacket(timestampUs / MICROS_PER_SECOND).await() ?: return null
    return packet.timestamp * MICROS_PER_SECOND
  }

  /**
   * Decoded audio samples covering `[startUs, endUs)`. An [endUs] that is not finite runs to the
   * end of the track.
   */
  suspend fun samples(
    startUs: Double,
    endUs: Double,
  ): SampleStream? {
    val track = audioTrack() ?: return null
    val sink = AudioSampleSink(track)
    val end = if (endUs.isFinite()) endUs / MICROS_PER_SECOND else OPEN_ENDED_SECONDS
    return SampleStream(sink.samples(startUs / MICROS_PER_SECOND, end))
  }

  fun close() {
    input.dispose()
  }

  /**
   * The track's real frame rate, measured over a bounded prefix of its packets rather than the
   * whole file.
   */
  private suspend fun measuredFrameRate(track: InputVideoTrack): Float? {
    val stats = track.computePacketStats(FRAME_RATE_SAMPLE_PACKETS).await()
    if (stats.packetCount < 2) return null
    val rate = stats.averagePacketRate
    return if (rate > 0.0 && rate.isFinite()) rate.toFloat() else null
  }

  companion object {
    /**
     * Opens [source], or returns null when a browser cannot read it. A URL is range-requested. A
     * byte array is copied into the JavaScript heap once.
     */
    fun of(source: MediaSource): SourceReader? {
      val backing: JsAny =
        when (source) {
          is MediaSource.Uri -> UrlSource(source.uri)
          is MediaSource.Bytes -> BufferSource(source.bytes.toUint8Array())
          is MediaSource.Path -> return null
        }
      return SourceReader(Input(inputOptions(backing)))
    }

    fun ofBuffer(buffer: ArrayBuffer): SourceReader = SourceReader(Input(inputOptions(BufferSource(buffer))))

    private fun inputOptions(backing: JsAny): JsAny =
      JsOptions()
        .put("source", backing)
        .put("formats", ALL_FORMATS)
        .build()
  }
}

/**
 * Decoded frames, pulled one at a time so the pipeline decodes only as fast as it encodes.
 */
internal class FrameStream(
  private val iterator: VideoSampleIterator,
) {
  private var finished = false

  suspend fun next(): VideoSample? {
    if (finished) return null
    val step = iterator.next().await()
    if (step.done) {
      finished = true
      return null
    }
    return step.value
  }

  /**
   * Releases the decoder. Iteration that stops on a trim boundary has to say so, or the decoder
   * stays alive holding frames nobody will read.
   */
  fun close() {
    if (finished) return
    finished = true
    iterator.`return`(null)
  }
}

/**
 * Random access into one video track: the frame at a time, or a forward run from a time.
 *
 * A sibling to [FrameStream] rather than a replacement for it. The export walks a track forwards and
 * needs nothing else, while a preview lands on an arbitrary position and then reads on from there.
 *
 * Every frame handed out is the caller's to close.
 */
internal class FrameSampler(
  private val sink: VideoSampleSink,
) {
  /**
   * The frame presented at [timestampUs], or null past the end of the track.
   */
  suspend fun sampleAt(timestampUs: Double): VideoSample? = sink.getSample(timestampUs / MICROS_PER_SECOND).await()

  /**
   * Frames from [startUs] onwards, up to [endUs]. An [endUs] that is not finite runs to the end of
   * the track.
   */
  fun stream(
    startUs: Double,
    endUs: Double,
  ): FrameStream {
    val end = if (endUs.isFinite()) endUs / MICROS_PER_SECOND else OPEN_ENDED_SECONDS
    return FrameStream(sink.samples(startUs / MICROS_PER_SECOND, end))
  }
}

/**
 * Decoded audio samples, pulled one at a time the same way [FrameStream] pulls frames.
 */
internal class SampleStream(
  private val iterator: AudioSampleIterator,
) {
  private var finished = false

  suspend fun next(): AudioSample? {
    if (finished) return null
    val step = iterator.next().await()
    if (step.done) {
      finished = true
      return null
    }
    return step.value
  }

  /**
   * Releases the decoder. Iteration that stops before the track's end has to say so, or the decoder
   * stays alive holding samples nobody will read.
   */
  fun close() {
    if (finished) return
    finished = true
    iterator.`return`(null)
  }
}

/**
 * The readers an export is using, keyed by source.
 *
 * A [MediaSource.Bytes] costs one copy into the JavaScript heap, so the copy is made once and both
 * the plan and the export that follows it read from the same one. [close] drops every reader and
 * the memory behind it.
 */
internal class SourceCache {
  private val readers = mutableMapOf<MediaSource, SourceReader>()

  fun open(source: MediaSource): SourceReader? {
    readers[source]?.let { return it }
    return runCatching { SourceReader.of(source) }.getOrNull()?.also { readers[source] = it }
  }

  fun close() {
    readers.values.forEach { it.close() }
    readers.clear()
  }
}

internal const val MICROS_PER_SECOND = 1_000_000.0

// mediabunny takes an end timestamp in seconds, and every real track ends long before this.
private const val OPEN_ENDED_SECONDS = 1e12

// Enough packets to measure a rate, few enough that measuring one does not walk a long file.
private const val FRAME_RATE_SAMPLE_PACKETS = 120
