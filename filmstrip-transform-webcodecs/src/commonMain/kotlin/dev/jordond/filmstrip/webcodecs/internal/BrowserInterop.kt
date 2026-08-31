@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray

// Almost everything the browser export touches is a typed external in webMain, shared by both
// targets. What is left here is the handful of things js and wasmJs genuinely spell differently:
// writing an object literal, and copying a Kotlin array into the JavaScript heap.

/**
 * An options object, built key by key because neither target can write a JavaScript literal from
 * shared code.
 */
internal expect class JsOptions() {
  fun put(
    key: String,
    value: String,
  ): JsOptions

  fun put(
    key: String,
    value: Int,
  ): JsOptions

  fun put(
    key: String,
    value: Double,
  ): JsOptions

  fun put(
    key: String,
    value: Boolean,
  ): JsOptions

  fun put(
    key: String,
    value: JsAny?,
  ): JsOptions

  fun build(): JsAny
}

/**
 * A one-element JavaScript array, for the blob constructor.
 */
internal expect fun jsArrayOf(value: JsAny): JsArray<JsAny>

/**
 * Copies uniform or vertex data into the JavaScript heap. Most call sites are nine floats or
 * fewer. A blurred fill's kernel weights are the one that runs long.
 */
internal expect fun FloatArray.toFloat32Array(): Float32Array

/**
 * Copies encoded media into the JavaScript heap so mediabunny can read it.
 *
 * The js target hands over the backing typed array as-is. wasmJs has to copy, which is the one
 * place a large [dev.jordond.filmstrip.media.MediaSource.Bytes] costs anything. A
 * [dev.jordond.filmstrip.media.MediaSource.Uri] is range-requested instead and never buffered.
 */
internal expect fun ByteArray.toUint8Array(): Uint8Array

/**
 * Copies bytes back out of the JavaScript heap, which is what a read-back frame arrives as.
 *
 * The js target views the same buffer. wasmJs copies, and does it in chunks rather than a byte at a
 * time, because a frame is megabytes and a call per byte would dominate the read.
 */
internal expect fun Uint8Array.toByteArray(): ByteArray

/**
 * True when the page has a `VideoEncoder` global at all. Reading the global directly would throw
 * where it is missing, which is why the check is spelled per target.
 */
internal expect fun hasVideoEncoder(): Boolean

/**
 * True when the page has an `AudioEncoder` global at all.
 */
internal expect fun hasAudioEncoder(): Boolean

/**
 * True when the page has an `AudioContext` global at all, which the preview's clock and its monitor
 * volume both run on.
 */
internal expect fun hasAudioContext(): Boolean

/**
 * VP9 Profile 2, 10-bit 4:2:0. The one HDR profile any browser encoder here was measured to take.
 */
internal const val HDR_VP9_CODEC: String = "vp09.02.10.10"

/**
 * The encoder codec string WebCodecs accepts, matched to the output size and the grade.
 *
 * H.264 and HEVC carry their level in the codec string, and a level too low for the frame is
 * refused with a message about coded area rather than about the encoder, so the string is picked
 * per size rather than probed once. A profile is 8-bit or 10-bit in the same way, which is why an
 * HDR export has to say so here rather than tagging an 8-bit stream on the way out.
 *
 * @param hdr Whether the encoder is being handed a grade to keep.
 */
internal fun webCodecString(
  codec: VideoCodec,
  size: Size,
  hdr: Boolean = false,
): String =
  when (codec) {
    VideoCodec.H264, VideoCodec.Hevc -> {
      // Neither has a browser encoder for its 10-bit profile, so the HDR ladder never picks one and
      // an 8-bit string here can never end up carrying a grade.
      check(!hdr) { "$codec has no browser encoder for a 10-bit profile" }
      when (codec) {
        VideoCodec.H264 -> {
          when {
            size.height > HEIGHT_1080 -> "avc1.420033"
            size.height > HEIGHT_720 -> "avc1.420028"
            else -> "avc1.42001F"
          }
        }
        else -> {
          when {
            size.height > HEIGHT_1080 -> "hvc1.1.6.L153.B0"
            size.height > HEIGHT_720 -> "hvc1.1.6.L120.B0"
            else -> "hvc1.1.6.L93.B0"
          }
        }
      }
    }
    VideoCodec.Vp9 -> {
      if (hdr) HDR_VP9_CODEC else "vp09.00.10.08"
    }
    VideoCodec.Auto -> {
      error("Auto is resolved by the planner before this is called")
    }
    VideoCodec.Vp8, VideoCodec.Av1 -> {
      error("$codec is never the ladder's encode pick, only ever copied across")
    }
  }

/**
 * The codec key mediabunny's encoder takes for [webCodecString]'s codec.
 */
internal fun muxCodecKey(codec: VideoCodec): String =
  when (codec) {
    VideoCodec.H264 -> "avc"
    VideoCodec.Hevc -> "hevc"
    VideoCodec.Vp9 -> "vp9"
    VideoCodec.Auto -> error("Auto is resolved by the planner before this is called")
    VideoCodec.Vp8, VideoCodec.Av1 -> error("$codec is never the ladder's encode pick, only ever copied across")
  }

/**
 * The container a codec belongs in: VP8, VP9 and AV1 go in WebM, the others in MP4.
 */
internal fun containerFor(codec: VideoCodec): String =
  when (codec) {
    VideoCodec.Vp8, VideoCodec.Vp9, VideoCodec.Av1 -> "webm"
    else -> "mp4"
  }

private const val HEIGHT_720 = 720
private const val HEIGHT_1080 = 1080
