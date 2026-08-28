package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAsynchronousCIImageFilteringRequest
import platform.CoreImage.CIImage

// macOS imports CIImage properly, so the members are already the type the harness wants.

@OptIn(ExperimentalForeignApi::class)
internal actual fun AVAsynchronousCIImageFilteringRequest.sourceFrame(): CIImage = sourceImage

@OptIn(ExperimentalForeignApi::class)
internal actual fun AVAsynchronousCIImageFilteringRequest.finishWithFrame(image: CIImage) {
  finishWithImage(image, context = null)
}
