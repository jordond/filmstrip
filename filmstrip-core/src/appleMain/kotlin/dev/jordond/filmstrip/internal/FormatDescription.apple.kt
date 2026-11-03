package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.formatDescriptions
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMFormatDescriptionGetTypeID
import platform.CoreMedia.CMFormatDescriptionRef
import platform.Foundation.CFBridgingRetain
import platform.darwin.NSObject

/**
 * This track's format description, retained, or null when it carries none.
 *
 * `formatDescriptions` hands its elements over as Objective-C objects, so casting one straight to
 * [CMFormatDescriptionRef] yields null however it looks. Going back across the toll-free bridge is
 * the only way to recover the pointer, and it hands over a reference the caller has to release
 * with `CFRelease`.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public fun AVAssetTrack.copyFormatDescription(): CMFormatDescriptionRef? {
  val bridged = CFBridgingRetain(formatDescriptions.firstOrNull() as? NSObject) ?: return null
  if (CFGetTypeID(bridged) != CMFormatDescriptionGetTypeID()) {
    CFRelease(bridged)
    return null
  }

  return bridged.reinterpret()
}
