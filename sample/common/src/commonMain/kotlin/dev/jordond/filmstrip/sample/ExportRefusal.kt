package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.CapabilitiesResult

/**
 * Why the device behind [capabilities] can't export at all, or null when it can or hasn't answered yet.
 *
 * A device that encodes no video has nothing to write a file with, though a preview still runs on it.
 */
public fun exportRefusalOf(capabilities: CapabilitiesResult?): String? =
  NO_VIDEO_ENCODER.takeIf { capabilities is CapabilitiesResult.Success && capabilities.capabilities.video.isEmpty() }

private const val NO_VIDEO_ENCODER = "This device can't encode video, so it can preview but not export."
