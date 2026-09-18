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

/**
 * Frees what [toMediaSource] minted for this source, which in a browser is the object URL.
 *
 * Calling it is optional. A URL that is never released lives until the page unloads, at the cost of
 * one blob registry entry and the handle on the picked file. Call it only once nothing reads the
 * source any more, since an export or a probe still running on a revoked URL fails. Everywhere else
 * this does nothing.
 */
public expect fun MediaSource.release()

/**
 * Frees what [toImageSource] minted for this source, the way [MediaSource.release] does.
 *
 * Optional on the same terms, and safe only once nothing reads the source any more.
 */
public expect fun ImageSource.release()
