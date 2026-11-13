@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.interop

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.Promise

// The browser globals the exporter touches, declared once for both targets. They are the same
// globals with the same shapes on js and wasmJs, so only the handful of things a target genuinely
// spells differently, building an object literal and copying an array into the JavaScript heap,
// lives behind expect/actual.

/**
 * Raw bytes owned by JavaScript. Allocated here for the plane bytes `VideoFrame.copyTo` fills.
 *
 * Core declares the same global as `dev.jordond.filmstrip.media.ArrayBuffer`, an interface it reads
 * into Kotlin and never constructs, so a buffer from one cannot be passed where the other is asked for.
 */
internal external class ArrayBuffer(
  length: Int,
) : JsAny {
  val byteLength: Int
}

/**
 * A byte view, which is what mediabunny's buffer source reads.
 */
internal external class Uint8Array : JsAny {
  constructor(length: Int)

  constructor(buffer: ArrayBuffer)

  val length: Int

  fun at(index: Int): Int

  /**
   * A view of the same bytes from [begin] up to [end], copying nothing.
   */
  fun subarray(
    begin: Int,
    end: Int,
  ): Uint8Array
}

/**
 * Fetches [input] with the request options in [init], which is how a byte range of a URL is asked for.
 */
internal external fun fetch(
  input: String,
  init: JsAny,
): Promise<Response>

/**
 * The answer to a [fetch].
 *
 * @property status The HTTP status, 206 when the server answered a range request with the range alone.
 */
internal external interface Response : JsAny {
  val status: Int

  fun arrayBuffer(): Promise<ArrayBuffer>
}

/**
 * A sixteen-bit view over one plane of a ten-bit frame, which is the shape `texImage2D` takes for
 * an `R16UI` upload.
 */
internal external class Uint16Array(
  buffer: ArrayBuffer,
  byteOffset: Int,
  length: Int,
) : JsAny {
  val length: Int

  fun at(index: Int): Int
}

/**
 * A float view, for uniforms and vertex data.
 */
internal external class Float32Array(
  length: Int,
) : JsAny {
  val length: Int

  fun at(index: Int): Float
}

/**
 * A WebCodecs frame. mediabunny hands one out per decoded sample, and it has to be closed
 * separately from the sample it came from.
 *
 * Only the image upload takes one. Everything that reads planes goes through the sample itself,
 * which forwards to the frame underneath it.
 */
internal external interface VideoFrame : JsAny {
  val colorSpace: VideoColorSpace

  fun close()
}

/**
 * What a decoded frame says of its colour.
 *
 * @property primaries The name of its color primaries, or null when it names none.
 * @property fullRange Whether its codes run the full range, or null when it does not say.
 */
internal external interface VideoColorSpace : JsAny {
  val primaries: JsString?
  val fullRange: JsAny?
}

/**
 * Where one plane sits inside the buffer a `copyTo` filled.
 *
 * The stride is the decoder's own and is wider than the frame on plenty of sources, so it is read
 * rather than computed.
 */
internal external interface PlaneLayout : JsAny {
  val offset: Int

  val stride: Int
}

/**
 * WebCodecs' decoder, used here only to ask whether a config decodes in software. Decoding itself
 * goes through mediabunny.
 */
internal external object VideoDecoder : JsAny {
  fun isConfigSupported(config: JsAny): Promise<DecoderSupport>
}

/**
 * The answer to `VideoDecoder.isConfigSupported`.
 */
internal external interface DecoderSupport : JsAny {
  val supported: Boolean
}

/**
 * A file handed to the page, either as an object URL or as a download.
 */
internal external class Blob(
  parts: JsArray<JsAny>,
  options: JsAny,
) : JsAny

/**
 * The wall clock, for naming a generated download.
 */
internal external object Date : JsAny {
  fun now(): Double
}

/**
 * WebCodecs' encoder, used here only to answer capability questions. Encoding itself goes through
 * mediabunny, which owns an encoder of its own and applies backpressure through it.
 */
internal external object VideoEncoder : JsAny {
  fun isConfigSupported(config: JsAny): Promise<EncoderSupport>
}

internal external object AudioEncoder : JsAny {
  fun isConfigSupported(config: JsAny): Promise<EncoderSupport>
}

/**
 * The answer to `isConfigSupported`.
 */
internal external interface EncoderSupport : JsAny {
  val supported: Boolean
}
