@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import platform.Foundation.writeToFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The clip is written atomically, so a file that exists is a file that finished downloading.
public actual suspend fun loadPreset(preset: SamplePreset): MediaSource {
  val files = NSFileManager.defaultManager
  val directory = NSTemporaryDirectory() + CACHE_DIRECTORY
  files.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
  val path = "$directory/${preset.fileName}"

  if (!files.fileExistsAtPath(path)) {
    val url = NSURL.URLWithString(preset.url) ?: error("${preset.url} is not a URL.")
    val data = download(url, preset.name)
    withContext(Dispatchers.Default) {
      check(data.writeToFile(path, atomically = true)) { "Downloaded ${preset.name} but could not write it to $path." }
    }
  }

  return MediaSource.of(path)
}

private suspend fun download(
  url: NSURL,
  name: String,
): NSData = suspendCancellableCoroutine { continuation ->
  val task = NSURLSession.sharedSession.dataTaskWithURL(url) { data, _, error ->
    when {
      error != null -> continuation.resumeWithException(IllegalStateException("Could not download $name: ${error.localizedDescription}"))
      data == null -> continuation.resumeWithException(IllegalStateException("The download of $name came back empty."))
      else -> continuation.resume(data)
    }
  }
  continuation.invokeOnCancellation { task.cancel() }
  task.resume()
}

internal actual val presetsAvailable: Boolean = true

private const val CACHE_DIRECTORY = "filmstrip-presets"
