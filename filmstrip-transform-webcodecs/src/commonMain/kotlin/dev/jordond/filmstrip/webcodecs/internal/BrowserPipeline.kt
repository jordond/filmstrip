package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * The export itself: every clip demuxed, decoded, drawn through one WebGL pass, encoded and muxed,
 * with the composition's audio mixed and encoded alongside it.
 *
 * The video loop is Kotlin rather than a JavaScript program handed over in one call, which is what
 * makes backpressure, cancellation between frames and `try`/`finally` around every frame possible at
 * all. Crossing the boundary once per frame costs a handle reference, not a copy.
 */
internal class BrowserPipeline(
  private val render: BrowserRender,
  private val sources: SourceCache,
) {
  /**
   * Runs the export and returns the muxed file, still in memory and not yet handed to anyone.
   *
   * The mix runs after every video frame is encoded, not before, so the video loop's own memory is
   * released before [BrowserAudioMix] starts allocating its own.
   *
   * @param onProgress Called after every encoded frame with the running count and the output
   *   timeline position it landed at.
   */
  suspend fun run(onProgress: suspend (Long, Double) -> Unit): PipelineResult {
    if (!render.writesVideo) return runAudioOnly()

    val compositor = BrowserCompositor.create(render.width, render.height, render.fill)
    try {
      val encoder = BrowserEncoder.open(render)
      var finished = false
      try {
        var emitted = 0L
        for (clip in render.clips) {
          emitted = encodeClip(clip, compositor, encoder, emitted, onProgress)
        }
        render.audioFormat?.let { format ->
          BrowserAudioMix.mixInto(render.audioTracks, format, render.duration, sources) { encoder.addAudio(it) }
        }
        val file = encoder.finish()
        finished = true
        return PipelineResult(file, emitted)
      } finally {
        if (!finished) encoder.cancel()
      }
    } finally {
      compositor.release()
    }
  }

  /**
   * Mixes the composition's audio into a file that carries no video track.
   *
   * No compositor is created and no clip is walked, so the run is the mix and nothing else. There
   * are no frames to report against, which is why nothing here calls back with progress.
   */
  private suspend fun runAudioOnly(): PipelineResult {
    val encoder = BrowserEncoder.open(render)
    var finished = false
    try {
      render.audioFormat?.let { format ->
        BrowserAudioMix.mixInto(render.audioTracks, format, render.duration, sources) { encoder.addAudio(it) }
      }
      val file = encoder.finish()
      finished = true
      return PipelineResult(file, 0)
    } finally {
      if (!finished) encoder.cancel()
    }
  }

  /**
   * Walks one clip's output slots, which is where the requested frame rate is honoured: a slot is
   * filled with whichever decoded frame sits closest to it, so a source at another rate is
   * duplicated or dropped into place rather than passed through at its own rate.
   */
  private suspend fun encodeClip(
    clip: RenderedClip,
    compositor: BrowserCompositor,
    encoder: BrowserEncoder,
    encodedSoFar: Long,
    onProgress: suspend (Long, Double) -> Unit,
  ): Long {
    val stepUs = MICROS_PER_SECOND / render.frameRate
    val stream =
      sources.open(clip.source)?.frames(clip.trimStartUs, clip.trimEndUs)
        ?: throw BrowserExportFailure(unreadable(clip.source))

    compositor.clip(clip)

    var emitted = encodedSoFar
    var current: VideoSample? = null
    var ahead: VideoSample? = stream.next()
    try {
      for (slot in 0 until clip.frames) {
        currentCoroutineContext().ensureActive()

        val sourceUs = clip.trimStartUs + slot * stepUs
        while (ahead != null && ahead.microsecondTimestamp <= sourceUs) {
          current?.close()
          current = ahead
          ahead = stream.next()
        }

        val chosen = nearest(current, ahead, sourceUs) ?: break
        compositor.draw(chosen)

        val outputUs = clip.offsetUs + slot * stepUs
        val frame = compositor.snapshot(outputUs, stepUs)
        try {
          encoder.add(frame)
        } finally {
          frame.close()
        }

        emitted++
        onProgress(emitted, outputUs)
      }
    } finally {
      current?.close()
      ahead?.close()
      stream.close()
    }
    return emitted
  }

  private fun nearest(
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

  private fun unreadable(source: MediaSource): String = "The browser could not read ${source.describe()}."
}

/**
 * What one run produced: the file, and how many frames actually went into it.
 */
internal class PipelineResult(
  val file: EncodedFile,
  val encodedFrames: Long,
)
