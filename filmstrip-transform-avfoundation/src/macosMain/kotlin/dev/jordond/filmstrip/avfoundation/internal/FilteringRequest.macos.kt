package dev.jordond.filmstrip.avfoundation.internal

import platform.AVFoundation.AVAsynchronousCIImageFilteringRequest
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage

internal actual fun AVAsynchronousCIImageFilteringRequest.sourceFrame(): CIImage = sourceImage

internal actual fun AVAsynchronousCIImageFilteringRequest.finish(
  image: CIImage,
  context: CIContext?,
) {
  finishWithImage(image, context)
}
