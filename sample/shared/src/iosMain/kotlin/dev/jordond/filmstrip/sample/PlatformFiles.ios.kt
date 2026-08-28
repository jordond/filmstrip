package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile

// The photo picker copies the chosen item into the app's temporary directory, so this is a plain
// sandbox path with no security scope to hold open.
public actual fun PlatformFile.toMediaSource(): MediaSource =
  nsUrl.path?.let(MediaSource::of) ?: MediaSource.ofUri(nsUrl.absoluteString.orEmpty())

public actual fun PlatformFile.toImageSource(): ImageSource =
  nsUrl.path?.let(ImageSource::of) ?: ImageSource.ofUri(nsUrl.absoluteString.orEmpty())
