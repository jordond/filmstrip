@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaSink

// A uri sink reports an object url of the finished file. The browser picks the url itself, so the one asked for names
// nothing.
public actual fun exportSink(): MediaSink = MediaSink.ofUri("blob:")

public actual fun saveExport(output: MediaSink) {
  if (output is MediaSink.Uri) downloadObjectUrl(output.uri)
}

public actual fun releaseExport(output: MediaSink) {
  if (output is MediaSink.Uri) revokeObjectUrl(output.uri)
}

private fun revokeObjectUrl(
  url: String,
): Unit = js("URL.revokeObjectURL(url)")

private fun downloadObjectUrl(
  url: String,
): Unit =
  js(
    """
    {
      fetch(url)
        .then(response => response.blob())
        .then(blob => {
          const extension = blob.type.includes('webm') ? 'webm' : blob.type.startsWith('audio') ? 'm4a' : 'mp4'
          const anchor = document.createElement('a')
          anchor.href = url
          anchor.download = "filmstrip-export-" + Date.now() + '.' + extension
          anchor.click()
        })
    }
    """,
  )
