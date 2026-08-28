package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAsynchronousCIImageFilteringRequest
import platform.CoreImage.CIImage

// AVFoundation's headers forward-declare CIImage on iOS and import it on macOS, so cinterop types
// the request's image members as two unrelated classes depending on the target. Commonisation drops
// a member whose signature differs like that, so appleMain sees the request with no image on it at
// all. These two are the whole of what it needs, declared here and reached per target.

/**
 * The frame the request wants filtered.
 */
@OptIn(ExperimentalForeignApi::class)
internal expect fun AVAsynchronousCIImageFilteringRequest.sourceFrame(): CIImage

/**
 * Hands [image] back as the filtered frame, letting the reader pull the next one.
 */
@OptIn(ExperimentalForeignApi::class)
internal expect fun AVAsynchronousCIImageFilteringRequest.finishWithFrame(image: CIImage)
