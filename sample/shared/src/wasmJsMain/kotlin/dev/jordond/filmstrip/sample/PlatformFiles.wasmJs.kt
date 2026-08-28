package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import org.w3c.dom.url.URL

// A browser hands back a File rather than a path, and WebCodecs reads a URL, so the picked file is
// published as an object url. The url lives as long as the document, which is the whole session.
public actual fun PlatformFile.toMediaSource(): MediaSource = MediaSource.ofUri(objectUrl())

public actual fun PlatformFile.toImageSource(): ImageSource = ImageSource.ofUri(objectUrl())

private fun PlatformFile.objectUrl(): String =
  when (val file = webFile) {
    is WebFile.FileWrapper -> URL.createObjectURL(file.file)
    is WebFile.DirectoryWrapper -> error("A directory cannot be read as media.")
  }
