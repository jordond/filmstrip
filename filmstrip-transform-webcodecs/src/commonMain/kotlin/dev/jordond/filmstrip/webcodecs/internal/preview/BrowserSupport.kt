@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.preview

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.interop.hasAudioContext
import dev.jordond.filmstrip.webcodecs.internal.interop.hasAudioDecoder
import dev.jordond.filmstrip.webcodecs.internal.interop.hasVideoDecoder
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Whether this page can give out the WebGL2 context the compositor draws on.
 *
 * Asked by taking one and handing it straight back, because a browser can carry the API and still
 * refuse a context on a driver it has blocklisted.
 */
@InternalFilmstripApi
public fun browserCanComposite(): Boolean =
  runCatching {
    val canvas = OffscreenCanvas(1, 1)
    val gl = canvas.getContext("webgl2", JsOptions().build()) ?: return false
    gl.getExtension("WEBGL_lose_context")?.loseContext()
    true
  }.getOrDefault(false)

/**
 * Whether this page carries the Web Audio API the preview's clock and monitor volume run on, and the WebCodecs
 * `AudioDecoder` that gives them anything to play.
 */
@InternalFilmstripApi
public fun browserCanMonitorAudio(): Boolean = hasAudioContext() && browserCanDecodeAudio()

/**
 * Whether this page carries the WebCodecs `VideoDecoder` every previewed frame is decoded through.
 */
@InternalFilmstripApi
public fun browserCanDecodeVideo(): Boolean = hasVideoDecoder()

/**
 * Whether this page carries the WebCodecs `AudioDecoder` every previewed sample is decoded through.
 */
@InternalFilmstripApi
public fun browserCanDecodeAudio(): Boolean = hasAudioDecoder()
