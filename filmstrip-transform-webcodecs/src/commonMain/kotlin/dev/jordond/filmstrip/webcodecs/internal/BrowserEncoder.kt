@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.media.URL
import dev.jordond.filmstrip.media.downloadBlob
import dev.jordond.filmstrip.webcodecs.internal.interop.ArrayBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBufferSource
import dev.jordond.filmstrip.webcodecs.internal.interop.Blob
import dev.jordond.filmstrip.webcodecs.internal.interop.BufferTarget
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.MP4
import dev.jordond.filmstrip.webcodecs.internal.interop.Mp4OutputFormat
import dev.jordond.filmstrip.webcodecs.internal.interop.Output
import dev.jordond.filmstrip.webcodecs.internal.interop.Quality
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSampleSource
import dev.jordond.filmstrip.webcodecs.internal.interop.WebMOutputFormat
import dev.jordond.filmstrip.webcodecs.internal.interop.jsArrayOf
import dev.jordond.filmstrip.webcodecs.internal.interop.muxAudioCodecKey
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The encode-and-mux half of the pipeline.
 *
 * mediabunny owns the WebCodecs encoder here rather than the pipeline, because its sample source
 * resolves [add] only once the encoder and the writer are ready for another frame. That is the
 * backpressure: a pipeline that awaits every add cannot outrun the encoder, however fast the
 * demuxer yields.
 *
 * Every frame is added with its own key frame flag, counted off [keyFrameSpacing] from the first frame. mediabunny's
 * own interval is measured in seconds against each frame's timestamp, which lands a key frame one frame off where a
 * timestamp rounds, so it is never left to decide.
 */
internal class BrowserEncoder private constructor(
  private val output: Output,
  private val videoSource: VideoSampleSource?,
  private val audioSource: AudioBufferSource?,
  private val target: BufferTarget,
  private val mimeType: String,
  private val keyFrameSpacing: Int,
) {
  private var added = 0L

  suspend fun add(frame: VideoSample) {
    val source = checkNotNull(videoSource) { NO_VIDEO_TRACK }
    val keyFrame = added % keyFrameSpacing == 0L
    added++
    source.add(frame, JsOptions().put("keyFrame", keyFrame).build()).await()
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
      val video = render.videoEncoding
      val container = render.container

      val target = BufferTarget()
      val format: JsAny = if (container == MP4) Mp4OutputFormat() else WebMOutputFormat()
      val output =
        Output(
          JsOptions()
            .put("format", format)
            .put("target", target)
            .build(),
        )

      val videoSource =
        if (video != null) {
          val source =
            VideoSampleSource(
              JsOptions()
                .put("codec", video.muxCodec)
                .put("fullCodecString", video.encoderCodec)
                .put("quality", Quality(JsOptions().put("bitrate", render.bitrate).build()))
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
                .put("codec", muxAudioCodecKey(render.audioCodec))
                .put("quality", Quality(JsOptions().put("bitrate", AUDIO_BITRATE).build()))
                .build(),
            )
          output.addAudioTrack(source, JsOptions().build())
          source
        }

      output.start().await()
      val mimeType = if (video != null) "video/$container" else "audio/$container"
      return BrowserEncoder(output, videoSource, audioSource, target, mimeType, render.keyFrameSpacing)
    }

    private const val AUDIO_BITRATE = 128_000
    private const val NO_BUFFER = "The muxer finalized without producing a buffer."
    private const val NO_VIDEO_TRACK = "This output writes no video track, so it takes no frames."
  }
}

/**
 * The codec strings one video encode is opened with.
 *
 * @property encoderCodec The WebCodecs string [BrowserEncoder] opens its encoder with.
 * @property muxCodec The codec key [BrowserEncoder] hands mediabunny.
 */
internal class VideoEncoding(
  val encoderCodec: String,
  val muxCodec: String,
)

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
   */
  fun download(filename: String): Unit = downloadBlob(blob(), filename)

  private fun blob(): Blob = Blob(jsArrayOf(buffer), JsOptions().put("type", mimeType).build())
}
