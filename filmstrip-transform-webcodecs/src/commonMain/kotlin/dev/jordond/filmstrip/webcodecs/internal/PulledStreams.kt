@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioSample
import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyIterator
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSampleSink
import dev.jordond.filmstrip.webcodecs.internal.interop.awaitOwned
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration.Companion.microseconds

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
 * Whichever of the two frames either side of [sourceUs] [nearestSourceFrame] picks.
 *
 * An export draws this frame at a slot, and a preview has to draw the same one.
 */
internal fun nearerOf(
  current: VideoSample?,
  ahead: VideoSample?,
  sourceUs: Double,
): VideoSample? {
  if (current == null || ahead == null) return current ?: ahead
  val times = listOf(current.microsecondTimestamp.microseconds, ahead.microsecondTimestamp.microseconds)
  return if (nearestSourceFrame(times, sourceUs.microseconds) == 1) ahead else current
}

/**
 * Decoded audio samples, pulled one at a time the same way [FrameStream] pulls frames.
 */
internal typealias SampleStream = PulledStream<AudioSample>
