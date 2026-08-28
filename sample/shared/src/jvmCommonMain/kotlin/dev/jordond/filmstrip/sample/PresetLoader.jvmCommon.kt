package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

// Android points java.io.tmpdir at the app's own cache directory, so one implementation covers the
// phone and the desktop. The download lands beside the destination and is renamed into place, so an
// interrupted fetch is never mistaken for a cached clip.
public actual suspend fun loadPreset(preset: SamplePreset): MediaSource = withContext(Dispatchers.IO) {
  val directory = File(System.getProperty("java.io.tmpdir"), CACHE_DIRECTORY).apply { mkdirs() }
  val cached = File(directory, preset.fileName)

  if (cached.length() != preset.bytes) {
    val partial = File(directory, "${preset.fileName}.part")
    URI(preset.url).toURL().openStream().use { input ->
      partial.outputStream().use(input::copyTo)
    }
    cached.delete()
    check(partial.renameTo(cached)) { "Downloaded ${preset.name} but could not move it to ${cached.path}." }
  }

  MediaSource.of(cached.absolutePath)
}

internal actual val presetsAvailable: Boolean = true

private const val CACHE_DIRECTORY = "filmstrip-presets"
