@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.await
import kotlin.js.Promise

// There is no filesystem to cache into, so the clip is fetched into a blob and published as an
// object url, which is the shape a picked file already arrives in. It works because the bucket
// serving the corpus answers with a cross-origin header, which `sample/media/cors.json` sets.
internal actual val presetsAvailable: Boolean = true

public actual suspend fun loadPreset(preset: SamplePreset): MediaSource =
  MediaSource.ofUri(fetchObjectUrl(preset.url).await<JsString>().toString())

private fun fetchObjectUrl(url: String): Promise<JsString> =
  js(
    """
    fetch(url)
      .then(response => {
        if (!response.ok) throw new Error('The download answered HTTP ' + response.status + '.')
        return response.blob()
      })
      .then(blob => URL.createObjectURL(blob))
    """,
  )
