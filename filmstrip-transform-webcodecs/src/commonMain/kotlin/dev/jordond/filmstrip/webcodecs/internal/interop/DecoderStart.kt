@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.interop

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsBoolean
import kotlin.js.unsafeCast
import kotlin.time.Duration

/**
 * How starting a `VideoDecoder` for a config ended.
 */
internal enum class DecoderStart {
  /** The decoder configured and its flush settled. */
  Started,

  /** The browser refused the config or the flush rejected. */
  Refused,

  /** The flush was still waiting at the deadline and the decoder was closed. */
  Expired,
}

/**
 * Starts a `VideoDecoder` for [config] and flushes it with nothing queued, waiting at most [deadline] for the flush.
 *
 * A browser can say a config is supported and still fail to start a decoder for it, such as HEVC where no hardware
 * decoder is reachable. The decoder is closed before this returns, including when the flush is still waiting at the
 * deadline.
 */
internal suspend fun videoDecoderStarts(
  config: JsAny,
  deadline: Duration,
): DecoderStart {
  val decoder = openVideoDecoder(config) ?: return DecoderStart.Refused
  try {
    val flushed =
      settleWithin(flushVideoDecoder(decoder), deadline.inWholeMilliseconds.toDouble()) {
        closeVideoDecoder(decoder)
      }.await()
    return when (flushed?.let { it.unsafeCast<JsBoolean>().toBoolean() }) {
      true -> DecoderStart.Started
      false -> DecoderStart.Refused
      null -> DecoderStart.Expired
    }
  } finally {
    closeVideoDecoder(decoder)
  }
}
