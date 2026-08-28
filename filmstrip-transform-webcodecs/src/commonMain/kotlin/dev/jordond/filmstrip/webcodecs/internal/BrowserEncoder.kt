@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import kotlinx.coroutines.await
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The encode-and-mux half of the pipeline.
 *
 * mediabunny owns the WebCodecs encoder here rather than the pipeline, because its sample source
 * resolves [add] only once the encoder and the writer are ready for another frame. That is the
 * backpressure: a pipeline that awaits every add cannot outrun the encoder, however fast the
 * demuxer yields.
 */
internal class BrowserEncoder private constructor(
  private val output: Output,
  private val videoSource: VideoSampleSource?,
  private val audioSource: AudioBufferSource?,
  private val target: BufferTarget,
  private val mimeType: String,
) {
  suspend fun add(frame: VideoSample) {
    checkNotNull(videoSource) { NO_VIDEO_TRACK }.add(frame).await()
  }

  /**
   * Adds the whole mix in one call. A null [audioSource] means the render carries no audio track,
   * and the buffer is dropped rather than encoded into a file that never claimed one.
   */
  suspend fun addAudio(buffer: AudioBuffer) {
    audioSource?.add(buffer)?.await()
  }

  /**
   * Flushes the encoder and closes the container.
   */
  suspend fun finish(): EncodedFile {
    output.finalize().await()
    val buffer = target.buffer ?: throw BrowserExportFailure(NO_BUFFER)
    return EncodedFile(buffer, mimeType)
  }

  /**
   * Tears the output down without writing a file. Safe to call after a failure or a cancellation,
   * including one that landed before the output was ever started.
   */
  fun cancel() {
    runCatching { output.cancel() }
  }

  companion object {
    suspend fun open(render: BrowserRender): BrowserEncoder {
      // A file with no video track is mp4 as well, since AAC is the only codec encoded here and
      // mp4 is what carries it.
      val container = if (render.writesVideo) checkNotNull(render.container) else MP4_CONTAINER

      val target = BufferTarget()
      val format: JsAny = if (container == MP4_CONTAINER) Mp4OutputFormat() else WebMOutputFormat()
      val output =
        Output(
          JsOptions()
            .put("format", format)
            .put("target", target)
            .build(),
        )

      val videoSource =
        if (render.writesVideo) {
          // Only ever asked for on a path that encodes video, where the planner always resolves
          // both of these.
          val source =
            VideoSampleSource(
              JsOptions()
                .put("codec", checkNotNull(render.muxCodec))
                .put("fullCodecString", checkNotNull(render.encoderCodec))
                .put("quality", Quality(JsOptions().put("bitrate", render.bitrate).build()))
                // In seconds, so the key frame spacing follows the real output rate instead of a
                // hardcoded frame count that means something different at every rate.
                .put("keyFrameInterval", KEY_FRAME_SECONDS)
                .put("hardwareAcceleration", "no-preference")
                .build(),
            )
          output.addVideoTrack(source, JsOptions().put("frameRate", render.frameRate).build())
          source
        } else {
          null
        }

      val audioSource =
        render.audioFormat?.let {
          val source =
            AudioBufferSource(
              JsOptions()
                .put("codec", AUDIO_CODEC)
                .put("quality", Quality(JsOptions().put("bitrate", AUDIO_BITRATE).build()))
                .build(),
            )
          output.addAudioTrack(source, JsOptions().build())
          source
        }

      output.start().await()
      val mimeType = if (render.writesVideo) "video/$container" else "audio/$container"
      return BrowserEncoder(output, videoSource, audioSource, target, mimeType)
    }

    private const val KEY_FRAME_SECONDS = 1.0
    private const val AUDIO_CODEC = "aac"
    private const val AUDIO_BITRATE = 128_000
    private const val MP4_CONTAINER = "mp4"
    private const val NO_BUFFER = "The muxer finalized without producing a buffer."
    private const val NO_VIDEO_TRACK = "This output writes no video track, so it takes no frames."
  }
}

/**
 * A finished file, still in memory.
 *
 * Nothing is handed to the caller until the file has been demuxed back, so a broken container is
 * caught before a download starts rather than after.
 */
internal class EncodedFile(
  private val buffer: ArrayBuffer,
  private val mimeType: String,
) {
  val byteLength: Int get() = buffer.byteLength

  /**
   * Reads the file back through a demuxer that never saw the encoder.
   */
  fun reader(): SourceReader = SourceReader.ofBuffer(buffer)

  /**
   * Mints an object URL for the file. The caller owns it and has to revoke it.
   */
  fun objectUrl(): String = URL.createObjectURL(blob())

  /**
   * Saves the file under [filename] through a download anchor.
   *
   * The URL is revoked on the next task rather than after an arbitrary delay: the browser has taken
   * its own reference to the blob by the time the click dispatch returns.
   */
  fun download(filename: String) {
    val url = URL.createObjectURL(blob())
    val anchor = document.createElement("a")
    anchor.href = url
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    setTimeout({ URL.revokeObjectURL(url) }, 0)
  }

  private fun blob(): Blob = Blob(jsArrayOf(buffer), JsOptions().put("type", mimeType).build())
}

/**
 * What reading the muxed file back proved: bytes that a demuxer accepts, frames a decoder yields,
 * and the timeline the frames actually cover.
 *
 * @property decodedFrames Video frames on a file that carries video, audio samples on one that
 *   carries only audio.
 */
internal class VerifiedFile(
  val codec: String,
  val decodedFrames: Long,
  val durationUs: Double,
)

/**
 * Demuxes and decodes the file through a reader that never saw the encoder.
 *
 * Counting bytes proves nothing, because a muxer that writes a broken index writes bytes too. The
 * duration comes from the walked samples rather than from the container, which reports zero for a
 * buffer the muxer only just finalized.
 */
internal suspend fun EncodedFile.verify(): VerifiedFile? = verified { readBack() }

/**
 * The same read-back for a file that carries audio and no video, where the video track [verify]
 * looks for is the one thing that is meant to be missing.
 */
internal suspend fun EncodedFile.verifyAudio(): VerifiedFile? = verified { readBackAudio() }

private suspend fun verified(read: suspend () -> VerifiedFile?): VerifiedFile? =
  try {
    read()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (unreadable: Throwable) {
    // A file the demuxer refuses outright fails verification the same way one that decodes short
    // does, and for the same reason: nobody should be handed it.
    null
  }

private suspend fun EncodedFile.readBack(): VerifiedFile? {
  val reader = reader()
  try {
    val codec =
      reader
        .videoTrack()
        ?.getCodec()
        ?.await()
        ?.toString() ?: return null
    val stream = reader.frames(0.0, Double.POSITIVE_INFINITY) ?: return null
    var decoded = 0L
    var durationUs = 0.0
    try {
      while (true) {
        val sample = stream.next() ?: break
        try {
          decoded++
          durationUs = maxOf(durationUs, sample.microsecondTimestamp + sample.microsecondDuration)
        } finally {
          sample.close()
        }
      }
    } finally {
      stream.close()
    }
    return VerifiedFile(codec, decoded, durationUs)
  } finally {
    reader.close()
  }
}

private suspend fun EncodedFile.readBackAudio(): VerifiedFile? {
  val reader = reader()
  try {
    val codec =
      reader
        .audioTrack()
        ?.getCodec()
        ?.await()
        ?.toString() ?: return null
    val stream = reader.samples(0.0, Double.POSITIVE_INFINITY) ?: return null
    var decoded = 0L
    var durationUs = 0.0
    try {
      while (true) {
        val sample = stream.next() ?: break
        try {
          decoded++
          durationUs = maxOf(durationUs, sample.microsecondTimestamp + sample.microsecondDuration)
        } finally {
          sample.close()
        }
      }
    } finally {
      stream.close()
    }
    return VerifiedFile(codec, decoded, durationUs)
  } finally {
    reader.close()
  }
}

/**
 * A browser-side failure with a message worth showing. Anything else that escapes the pipeline is
 * reported with whatever the platform said.
 */
internal class BrowserExportFailure(
  message: String,
) : Exception(message)
