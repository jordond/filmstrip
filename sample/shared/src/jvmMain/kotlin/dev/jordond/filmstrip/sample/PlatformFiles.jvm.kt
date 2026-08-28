package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path

// The desktop picker hands back a real file on disk, which is what the ffmpeg backend wants.
public actual fun PlatformFile.toMediaSource(): MediaSource = MediaSource.of(path)

public actual fun PlatformFile.toImageSource(): ImageSource = ImageSource.of(path)
