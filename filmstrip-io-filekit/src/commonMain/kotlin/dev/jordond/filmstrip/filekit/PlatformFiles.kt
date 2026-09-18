package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile

/**
 * Reads [PlatformFile] as video.
 *
 * A picked file is a file or a content uri on Android, a file url on Apple and an object url in a
 * browser, so each target returns the arm of [MediaSource] its export engine understands rather
 * than flattening every one of them to a path.
 */
public expect fun PlatformFile.toMediaSource(): MediaSource

/**
 * Reads [PlatformFile] as a still image, for a watermark or a title card.
 *
 * Maps the same way [toMediaSource] does.
 */
public expect fun PlatformFile.toImageSource(): ImageSource

/**
 * Writes an export to [PlatformFile].
 *
 * The file has to exist and be writable before the export runs, which is what the picker's file
 * saver hands back. A browser has no such thing, so there the result is a download named after the
 * file rather than a write into it.
 */
public expect fun PlatformFile.toMediaSink(): MediaSink
