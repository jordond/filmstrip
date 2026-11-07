@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

// There is no filesystem to cache into, so the clip is fetched into a blob and published as an
// object url, which is the shape a picked file already arrives in. It works because the bucket
// serving the corpus answers with a cross-origin header, which `sample/media/cors.json` sets.
public actual val presetsAvailable: Boolean = true

public actual suspend fun loadPreset(preset: SamplePreset): MediaSource = MediaSource.ofUri(fetchObjectUrl(preset.url))

// Cancelling aborts the download, and an object url nobody is left to take is revoked.
private suspend fun fetchObjectUrl(url: String): String =
  suspendCancellableCoroutine { continuation ->
    val controller = AbortController()
    continuation.invokeOnCancellation { controller.abort() }
    fetchIntoObjectUrl(url, controller.signal).then<JsAny?>(
      onFulfilled = { objectUrl ->
        continuation.resume(objectUrl.toString()) { _, _, _ -> revokeObjectUrl(objectUrl) }
        null
      },
      onRejected = { reason ->
        continuation.resumeWithException(
          reason.toThrowableOrNull() ?: IllegalStateException("The download failed: $reason"),
        )
        null
      },
    )
  }

private external class AbortController : JsAny {
  val signal: JsAny

  fun abort()
}

private fun fetchIntoObjectUrl(
  url: String,
  signal: JsAny,
): Promise<JsString> =
  js(
    """
    fetch(url, { signal })
      .then(response => {
        if (!response.ok) throw new Error('The download answered HTTP ' + response.status + '.')
        return response.blob()
      })
      .then(blob => {
        signal.throwIfAborted()
        return URL.createObjectURL(blob)
      })
    """,
  )

private fun revokeObjectUrl(url: JsString): Unit = js("URL.revokeObjectURL(url)")
