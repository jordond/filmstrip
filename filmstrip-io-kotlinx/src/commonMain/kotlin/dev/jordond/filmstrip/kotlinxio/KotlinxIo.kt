package dev.jordond.filmstrip.kotlinxio

import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.io.Source
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

// There is no sink from a RawSink here on purpose. Every export engine writes to a location of its
// own choosing, so the honest shape is to export to MediaSink.Temporary and copy the result with
// SystemFileSystem.source(output).transferTo(yourSink).

/**
 * Reads video from this path.
 *
 * The path is passed through as its own string, so it means whatever the platform's filesystem
 * means by it. A browser reads no path at all, and the capabilities table lists which backend takes
 * which arm.
 */
public fun Path.toMediaSource(): MediaSource = MediaSource.of(toString())

/**
 * Reads a still image from this path, for a watermark or a title card.
 *
 * The path is passed through as its own string, the way [toMediaSource] passes it.
 */
public fun Path.toImageSource(): ImageSource = ImageSource.of(toString())

/**
 * Writes an export to this path.
 *
 * The parent directory has to exist and be writable. The path is passed through as its own string,
 * the way [toMediaSource] passes it.
 */
public fun Path.toMediaSink(): MediaSink = MediaSink.of(toString())

/**
 * Reads the whole of this source into a media source, consuming it.
 *
 * Every byte lands in memory at once, so this is for small assets and for the browser, where a path
 * means nothing.
 *
 * @param hint What container the bytes hold, or null to let the backend sniff it.
 */
public fun Source.readMediaSource(hint: FormatHint? = null): MediaSource = MediaSource.ofBytes(readByteArray(), hint)

/**
 * Reads the whole of this source into an image source, consuming it.
 *
 * Every byte lands in memory at once, the way [readMediaSource] reads them.
 */
public fun Source.readImageSource(): ImageSource = ImageSource.ofBytes(readByteArray())
