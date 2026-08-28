package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile

/**
 * Turns a file FileKit handed back into something filmstrip can read.
 *
 * A picked file is a content uri on Android and a file url on iOS, so each target reaches for the
 * arm of [MediaSource] its backend understands rather than flattening both to a path.
 */
public expect fun PlatformFile.toMediaSource(): MediaSource

/**
 * The same, for the image a watermark composites over the video.
 */
public expect fun PlatformFile.toImageSource(): ImageSource
