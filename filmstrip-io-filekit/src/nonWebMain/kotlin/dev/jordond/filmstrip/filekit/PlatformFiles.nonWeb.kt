package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.PlatformFile

/**
 * Hands an export result back to FileKit, for a share sheet or a save dialog.
 *
 * Android, Apple and the JVM only. A browser sink names a download rather than a file that already
 * exists, so there is nothing to hand back there.
 *
 * @throws IllegalArgumentException on a [MediaSink.Uri] this platform cannot parse. Which uris
 *   those are is per platform, since each one hands the string to its own URL type.
 * @throws IllegalStateException on [MediaSink.Temporary], which is a request rather than a
 *   location. Read the resolved path off `ExportStatus.Success.output` instead.
 */
public expect fun MediaSink.toPlatformFile(): PlatformFile
