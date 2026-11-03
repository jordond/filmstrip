package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.filePathOf
import io.github.vinceglb.filekit.PlatformFile
import java.io.File

// The desktop picker hands back a real file on disk, which is the only thing the ffmpeg backend
// reads and the only thing it writes.
public actual fun PlatformFile.toMediaSource(): MediaSource = MediaSource.of(file.absolutePath)

public actual fun PlatformFile.toImageSource(): ImageSource = ImageSource.of(file.absolutePath)

public actual fun PlatformFile.toMediaSink(): MediaSink = MediaSink.of(file.absolutePath)

internal actual fun platformFileAt(path: String): PlatformFile = PlatformFile(File(path))

// Read into a path by the same decoder the ffmpeg backend resolves a uri with, so the file handed back is the one the
// export wrote. Anything but a file: URL naming a file on this machine is refused, since the backend only ever writes
// to a local path.
internal actual fun platformFileOf(uri: String): PlatformFile =
  PlatformFile(File(filePathOf(uri) ?: throw IllegalArgumentException("$uri is not a local file: URL.")))
