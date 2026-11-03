package dev.jordond.filmstrip.avfoundation.internal.interop

import dev.jordond.filmstrip.internal.copyFormatDescription
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAssetTrack
import platform.CoreMedia.CMFormatDescriptionRef

/**
 * The first track's format description, retained, for a writer input that is passing samples
 * through instead of encoding them.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun List<*>.copyFormatHint(): CMFormatDescriptionRef? =
  (firstOrNull() as? AVAssetTrack)?.copyFormatDescription()
