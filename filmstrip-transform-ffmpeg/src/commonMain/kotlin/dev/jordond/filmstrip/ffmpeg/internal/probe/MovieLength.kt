package dev.jordond.filmstrip.ffmpeg.internal.probe

import kotlin.time.Duration

/**
 * How long the MP4 or QuickTime file at [path] plays for, as core reads it off the file's own boxes.
 *
 * Null for any other container and for a file that cannot be opened or read, which leaves the length to ffprobe.
 */
internal expect suspend fun movieLengthAt(path: String): Duration?
