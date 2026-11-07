@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.internal.awaitCatching
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.webcodecs.internal.interop.ALL_FORMATS
import dev.jordond.filmstrip.webcodecs.internal.interop.ArrayBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioSample
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioSampleSink
import dev.jordond.filmstrip.webcodecs.internal.interop.BufferSource
import dev.jordond.filmstrip.webcodecs.internal.interop.EncodedPacketSink
import dev.jordond.filmstrip.webcodecs.internal.interop.Input
import dev.jordond.filmstrip.webcodecs.internal.interop.InputAudioTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.InputVideoTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyIterator
import dev.jordond.filmstrip.webcodecs.internal.interop.UrlSource
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoDecoder
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSampleSink
import dev.jordond.filmstrip.webcodecs.internal.interop.awaitOwned
import dev.jordond.filmstrip.webcodecs.internal.interop.jsCopyWith
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.DurationUnit

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
  private var tenBit: Boolean? = null
  private var softwareHint = false

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
      frameRate = track.measuredFrameRate(),
    )
  }

  /**
   * Whether this source hands out a frame whose ten-bit planes can be read, which is what a kept
   * grade needs.
   *
   * A browser's default decoder keeps an HDR frame opaque above some size, and an opaque frame
   * refuses `copyTo`, so the software decoder is asked for first and the default is the fallback.
   * HEVC has no software decoder at all here, which is what makes this a per-source answer rather
   * than a per-device one. Probed once and cached, so the plan, the preview and the export ask the
   * same reader for the same answer.
   */
  suspend fun readsTenBit(): Boolean = tenBit ?: probeTenBit().also { tenBit = it }

  /**
   * Decoded frames covering `[startUs, endUs)`. An [endUs] that is not finite runs to the end of
   * the track.
   *
   * @param tenBit Whether the caller reads the frame's planes rather than uploading it as an image.
   *   Opens whichever decoder [readsTenBit] found a readable frame through.
   */
  suspend fun frames(
    startUs: Double,
    endUs: Double,
    tenBit: Boolean = false,
  ): FrameStream? {
    val track = videoTrack() ?: return null
    val sink = sinkFor(track, tenBit)
    val end = if (endUs.isFinite()) endUs / MICROS_PER_SECOND else OPEN_ENDED_SECONDS
    return FrameStream(sink.samples(startUs / MICROS_PER_SECOND, end)) { it.close() }
  }

  /**
   * A sampler over this source's video track, for a caller that reads frames out of order.
   *
   * Each call opens a decoder of its own, so a preview can read a frame back without disturbing the
   * one playback is decoding through.
   *
   * @param tenBit The same as on [frames].
   */
  suspend fun sampler(tenBit: Boolean = false): FrameSampler? {
    val track = videoTrack() ?: return null
    return FrameSampler(sinkFor(track, tenBit))
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
   * Where a stream copy of this source could open for a cut at [cut], which is the sync sample at
   * or before it, or null when there is none for a copy to open on.
   *
   * A source with no video track has nothing to look in, and a sample mediabunny names past the cut
   * would leave the copy opening after the frame it has to carry, so both read as none at all. The
   * planner decides from this whether the copy is reachable, and this backend never asks again.
   */
  suspend fun syncSampleAtOrBefore(cut: Duration): Duration? {
    val keyUs = keyFrameAt(cut.toDouble(DurationUnit.MICROSECONDS)) ?: return null
    return keyUs.microseconds.takeIf { it <= cut }
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
    return SampleStream(sink.samples(startUs / MICROS_PER_SECOND, end)) { it.close() }
  }

  fun close() {
    input.dispose()
  }

  /**
   * The sink a caller reading planes decodes through, or the plain one otherwise.
   */
  private suspend fun sinkFor(
    track: InputVideoTrack,
    tenBit: Boolean,
  ): VideoSampleSink {
    if (!tenBit) return VideoSampleSink(track)
    readsTenBit()
    return if (softwareHint) softwareSink(track) ?: VideoSampleSink(track) else VideoSampleSink(track)
  }

  /**
   * Decodes one frame each way and answers on what came back.
   *
   * The software hint is put to `VideoDecoder` first, since a config it refuses outright is not
   * worth opening a sink for. mediabunny still refuses the hint on some tracks that answered
   * supported, so the default decoder is tried after it rather than the source being written off.
   */
  private suspend fun probeTenBit(): Boolean {
    val track = videoTrack() ?: return false
    if (softwareDecodes(track) && yieldsTenBit { softwareSink(track) }) {
      softwareHint = true
      return true
    }
    softwareHint = false
    return yieldsTenBit { VideoSampleSink(track) }
  }

  private suspend fun softwareDecodes(track: InputVideoTrack): Boolean {
    val config = track.getDecoderConfig().await() ?: return false
    return awaitCatching {
      VideoDecoder
        .isConfigSupported(jsCopyWith(config, HARDWARE_ACCELERATION, SOFTWARE_HINT))
        .await()
        .supported
    }.getOrDefault(false)
  }

  private fun softwareSink(track: InputVideoTrack): VideoSampleSink? =
    runCatching {
      VideoSampleSink(track, JsOptions().put(HARDWARE_ACCELERATION, SOFTWARE_HINT).build())
    }.getOrNull()

  /**
   * Whether the first frame [open] decodes carries readable ten-bit planes. A decoder that refuses
   * the track answers no rather than failing the plan.
   */
  private suspend fun yieldsTenBit(open: () -> VideoSampleSink?): Boolean {
    val sample = awaitCatching { open()?.getSample(0.0)?.awaitOwned { it?.close() } }.getOrNull() ?: return false
    return try {
      sample.format?.toString() == TEN_BIT_FORMAT
    } finally {
      sample.close()
    }
  }

  companion object {
    /**
     * Opens [source], or returns null when a browser cannot read it. A URL is range-requested. A
     * byte array is copied into the JavaScript heap once. A source mediabunny refuses while its
     * input is built reads as null too, so this never throws.
     */
    fun of(source: MediaSource): SourceReader? =
      try {
        backingOf(source)?.let { SourceReader(Input(inputOptions(it))) }
      } catch (refused: Throwable) {
        null
      }

    fun ofBuffer(buffer: ArrayBuffer): SourceReader = SourceReader(Input(inputOptions(BufferSource(buffer))))

    private fun backingOf(source: MediaSource): JsAny? =
      when (source) {
        is MediaSource.Uri -> UrlSource(source.uri)
        is MediaSource.Bytes -> BufferSource(source.bytes.toUint8Array())
        is MediaSource.Path -> null
        // A still has no container for the demuxer to open.
        is MediaSource.Image -> null
      }

    private fun inputOptions(backing: JsAny): JsAny =
      JsOptions()
        .put("source", backing)
        .put("formats", ALL_FORMATS)
        .build()
  }
}

/**
 * What a mediabunny sink yields, pulled one at a time so a reader is fed only as fast as it reads.
 *
 * @param release Frees a value whose reader was cancelled before it got it. A value with nothing to
 * free passes an empty one.
 */
internal class PulledStream<T : JsAny>(
  private val iterator: MediabunnyIterator<T>,
  private val release: (T) -> Unit,
) {
  private var finished = false

  /**
   * The next value, or null once the sink has yielded its last or this was closed.
   */
  suspend fun next(): T? {
    if (finished) return null
    val step =
      iterator.next().awaitOwned { late ->
        if (!late.done) late.value?.let(release)
      }
    if (step.done) {
      finished = true
      return null
    }
    return step.value
  }

  /**
   * Releases what the sink holds for this stream. A reader that stops before the track's end has to say so, or the
   * sink stays alive holding what nobody will read.
   */
  fun close() {
    if (finished) return
    finished = true
    iterator.`return`(null)
  }
}

/**
 * Decoded frames, pulled one at a time so the pipeline decodes only as fast as it encodes.
 */
internal typealias FrameStream = PulledStream<VideoSample>

/**
 * Random access into one video track: the frame at a time, or a forward run from a time.
 *
 * A sibling to [FrameStream] rather than a replacement for it. The export walks a track forwards and
 * needs nothing else, while a preview lands on an arbitrary position and then reads on from there.
 *
 * Every frame handed out is the caller's to close.
 *
 * @param release Frees a frame whose caller was cancelled before it got it.
 */
internal class FrameSampler(
  private val sink: VideoSampleSink,
  private val release: (VideoSample) -> Unit = { it.close() },
) {
  /**
   * The frame presented at [timestampUs], or null before the track's first frame. Past the end of
   * the track that is the last frame.
   */
  suspend fun sampleAt(timestampUs: Double): VideoSample? =
    sink.getSample(timestampUs / MICROS_PER_SECOND).awaitOwned { late -> late?.let(release) }

  /**
   * The frame an export draws at [timestampUs], picked by [nearerOf] from the two straddling it, up to [endUs]. Null
   * when the track has no frame there at all. The other frame is closed.
   */
  suspend fun nearestAt(
    timestampUs: Double,
    endUs: Double,
  ): VideoSample? {
    val frames = stream(timestampUs, endUs)
    try {
      return nearestOf(timestampUs, release, frames::next) ?: sampleAt(timestampUs)
    } finally {
      frames.close()
    }
  }

  /**
   * Frames from [startUs] onwards, up to [endUs]. An [endUs] that is not finite runs to the end of
   * the track.
   */
  fun stream(
    startUs: Double,
    endUs: Double,
  ): FrameStream {
    val end = if (endUs.isFinite()) endUs / MICROS_PER_SECOND else OPEN_ENDED_SECONDS
    return FrameStream(sink.samples(startUs / MICROS_PER_SECOND, end), release)
  }
}

/**
 * The frame [nearerOf] picks at [timestampUs] from what [next] hands over, reading a second frame only when the first
 * sits before that time. Null when [next] has nothing at all.
 *
 * The frame not chosen goes to [release], and so does the first frame when reading the second fails or is cancelled.
 */
internal suspend fun nearestOf(
  timestampUs: Double,
  release: (VideoSample) -> Unit,
  next: suspend () -> VideoSample?,
): VideoSample? {
  val current = next() ?: return null
  if (current.microsecondTimestamp >= timestampUs) return current
  val ahead =
    try {
      next()
    } catch (failure: Throwable) {
      release(current)
      throw failure
    } ?: return current
  val chosen = nearerOf(current, ahead, timestampUs)
  if (chosen !== current) release(current)
  if (chosen !== ahead) release(ahead)
  return chosen
}

/**
 * Whichever of the two frames either side of [sourceUs] sits closer to it, the earlier one on a tie.
 *
 * An export draws this frame at a slot, and a preview has to draw the same one.
 */
internal fun nearerOf(
  current: VideoSample?,
  ahead: VideoSample?,
  sourceUs: Double,
): VideoSample? =
  when {
    current == null -> ahead
    ahead == null -> current
    abs(ahead.microsecondTimestamp - sourceUs) < abs(current.microsecondTimestamp - sourceUs) -> ahead
    else -> current
  }

/**
 * Decoded audio samples, pulled one at a time the same way [FrameStream] pulls frames.
 */
internal typealias SampleStream = PulledStream<AudioSample>

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
    return SourceReader.of(source)?.also { readers[source] = it }
  }

  fun close() {
    readers.values.forEach { it.close() }
    readers.clear()
  }
}

/**
 * The message an export fails with when the browser cannot open [source] or finds no track in it to draw. The
 * pipeline and the stream copy both throw it.
 */
internal fun unreadableMessage(source: MediaSource): String = "The browser could not read ${source.describe()}."

/**
 * The error a probe or an export answers with when the browser cannot open [source]. Its message says what a browser
 * reads and that a file has to be loaded by the page first.
 */
internal fun unreadableSource(source: MediaSource): FilmstripError.SourceUnreadable =
  FilmstripError.SourceUnreadable(source.describe(), UNREADABLE_REASON)

private const val UNREADABLE_REASON =
  "A browser reads URLs and in-memory bytes. A path has no meaning here, and a file has to be " +
    "loaded by the page first."

internal const val MICROS_PER_SECOND = 1_000_000.0

/**
 * The four-two-zero ten-bit planar layout a kept grade is read out of. The one format any browser
 * decoder here was measured to hand over for an HDR source.
 */
internal const val TEN_BIT_FORMAT: String = "I420P10"

private const val HARDWARE_ACCELERATION = "hardwareAcceleration"
private const val SOFTWARE_HINT = "prefer-software"

// mediabunny takes an end timestamp in seconds, and every real track ends long before this.
private const val OPEN_ENDED_SECONDS = 1e12

/**
 * The track's real frame rate, measured over a bounded prefix of its packets rather than the whole file.
 *
 * @return The rate, or null when the prefix holds fewer than two packets or measures no finite positive rate.
 */
internal suspend fun InputVideoTrack.measuredFrameRate(): Float? {
  val stats = computePacketStats(FRAME_RATE_SAMPLE_PACKETS).await()
  if (stats.packetCount < 2) return null
  val rate = stats.averagePacketRate
  return if (rate > 0.0 && rate.isFinite()) rate.toFloat() else null
}

// Enough packets to measure a rate, few enough that measuring one does not walk a long file.
private const val FRAME_RATE_SAMPLE_PACKETS = 120
