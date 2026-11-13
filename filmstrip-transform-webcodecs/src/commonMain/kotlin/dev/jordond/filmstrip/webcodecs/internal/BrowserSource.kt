@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.internal.awaitCatching
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.audioTrackIdOf
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.movieAudioTrackIdsOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.ALL_FORMATS
import dev.jordond.filmstrip.webcodecs.internal.interop.ArrayBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioSampleSink
import dev.jordond.filmstrip.webcodecs.internal.interop.BufferSource
import dev.jordond.filmstrip.webcodecs.internal.interop.EncodedPacketSink
import dev.jordond.filmstrip.webcodecs.internal.interop.Input
import dev.jordond.filmstrip.webcodecs.internal.interop.InputAudioTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.InputVideoTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.UrlSource
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoDecoder
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSampleSink
import dev.jordond.filmstrip.webcodecs.internal.interop.asDouble
import dev.jordond.filmstrip.webcodecs.internal.interop.asString
import dev.jordond.filmstrip.webcodecs.internal.interop.audioTrackList
import dev.jordond.filmstrip.webcodecs.internal.interop.awaitOwned
import dev.jordond.filmstrip.webcodecs.internal.interop.firstVideoTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.jsCopyWith
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
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
  private val headers: HeaderBytes,
) {
  private var track: InputVideoTrack? = null
  private var looked = false
  private var audioTracks: List<InputAudioTrack>? = null
  private var audioTrackIds: List<AudioTrackId>? = null
  private var tenBit: Boolean? = null
  private var softwareHint = false

  suspend fun videoTrack(): InputVideoTrack? {
    if (!looked) {
      track = input.firstVideoTrack()
      looked = true
    }
    return track
  }

  /**
   * The audio track a clip plays, the one [chosen] names, or null when it plays none.
   *
   * The track is found by the same id the prober gave it, so the planner's choice and the track read here are one.
   */
  suspend fun audioTrack(chosen: ResolvedAudioTrack?): InputAudioTrack? {
    chosen ?: return null
    return audioTracks().getOrNull(audioTrackIds().indexOf(chosen.id))
  }

  /**
   * The [AudioTrackId] of each of [audioTracks], in the same order.
   *
   * A file mediabunny read as MP4 or QuickTime has its ids read off its own track headers by [movieAudioTrackIdsOf],
   * since mediabunny reports a track's place and not the id its header carries. Any other file is named by place
   * through [audioTrackIdOf].
   *
   * mediabunny gives no key to pair a track with its header but their order, and it drops a sound track whose header
   * it cannot use. For such a file the tracks are paired with the sound headers by place, so a track listed after the
   * dropped one takes the id of the header before its own.
   */
  suspend fun audioTrackIds(): List<AudioTrackId> =
    audioTrackIds ?: run {
      val count = audioTracks().size
      if (input.getFormat().await().mimeType in MOVIE_FORMATS) {
        val size =
          input.source
            .getSize()
            .await()
            .asDouble()
            .toLong()
        movieAudioTrackIdsOf(size, headers::read, count)
      } else {
        List(count) { ordinal -> audioTrackIdOf(isMovie = false, trackId = null, ordinal = ordinal) }
      }
    }.also { audioTrackIds = it }

  /**
   * Whether the file's container can flag a track as the default, which only an MP4, QuickTime, Matroska or WebM file
   * does.
   *
   * mediabunny gives a track in any other container its own default disposition, which flags every track.
   */
  suspend fun flagsDefaultTrack(): Boolean = input.getFormat().await().mimeType in DEFAULT_FLAG_FORMATS

  /**
   * The first audio track the file lists, or null when it carries none.
   *
   * A file filmstrip wrote carries one at most, so reading one back takes this. A clip's track comes from [audioTrack].
   */
  suspend fun firstAudioTrack(): InputAudioTrack? = audioTracks().firstOrNull()

  suspend fun audioTracks(): List<InputAudioTrack> = audioTracks ?: input.audioTrackList().also { audioTracks = it }

  /**
   * The file's duration, in microseconds. The largest end timestamp among all its tracks, so a
   * source with audio running past its video reads its real length rather than the video's.
   */
  suspend fun durationUs(): Double = input.computeDuration().await().asDouble() * MICROS_PER_SECOND

  suspend fun videoInfo(): SourceVideoInfo? {
    val track = videoTrack() ?: return null
    return SourceVideoInfo(
      durationUs = track.computeDuration().await().asDouble() * MICROS_PER_SECOND,
      width =
        track
          .getDisplayWidth()
          .await()
          .asDouble()
          .toInt(),
      height =
        track
          .getDisplayHeight()
          .await()
          .asDouble()
          .toInt(),
      rotationDegrees =
        track
          .getRotation()
          .await()
          .asDouble()
          .toInt(),
      codec = track.getCodec().await()?.asString() ?: return null,
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
   * Decoded audio samples of a written file's [firstAudioTrack] covering `[startUs, endUs)`. An [endUs] that is not
   * finite runs to the end of the track.
   */
  suspend fun samples(
    startUs: Double,
    endUs: Double,
  ): SampleStream? {
    val track = firstAudioTrack() ?: return null
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
      sample.format?.asString() == TEN_BIT_FORMAT
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
        when (source) {
          is MediaSource.Uri -> {
            SourceReader(Input(inputOptions(UrlSource(source.uri))), headerBytesOf(source.uri))
          }
          is MediaSource.Bytes -> {
            SourceReader(Input(inputOptions(BufferSource(source.bytes.toUint8Array()))), headerBytesOf(source.bytes))
          }
          is MediaSource.Path -> {
            null
          }
          // A still has no container for the demuxer to open.
          is MediaSource.Image -> {
            null
          }
        }
      } catch (refused: Throwable) {
        null
      }

    fun ofBuffer(buffer: ArrayBuffer): SourceReader =
      SourceReader(Input(inputOptions(BufferSource(buffer))), headerBytesOf(buffer))

    private fun inputOptions(backing: JsAny): JsAny =
      JsOptions()
        .put("source", backing)
        .put("formats", ALL_FORMATS)
        .build()
  }
}

// The MIME types mediabunny gives the MP4 and QuickTime formats, whose track headers carry the ids a track is named by.
private val MOVIE_FORMATS = setOf("video/mp4", "video/quicktime")

// The MIME types mediabunny gives the formats whose containers flag a default track.
private val DEFAULT_FLAG_FORMATS = MOVIE_FORMATS + setOf("video/x-matroska", "video/webm")

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
internal const val OPEN_ENDED_SECONDS = 1e12

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
