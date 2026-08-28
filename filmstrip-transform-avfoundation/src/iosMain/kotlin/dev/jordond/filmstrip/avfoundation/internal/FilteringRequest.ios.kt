package dev.jordond.filmstrip.avfoundation.internal

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import platform.AVFoundation.AVAsynchronousCIImageFilteringRequest
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage

// Reinterpreted, not cast. `objcnames.classes.CIImage` and the real one are unrelated as far as the
// compiler is concerned, so a cast that way round is rejected as one that can never succeed, even
// though the pointer under both is the same object.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun AVAsynchronousCIImageFilteringRequest.sourceFrame(): CIImage =
  interpretObjCPointer(sourceImage.objcPtr())

// The other direction is a cast the compiler accepts, which is the documented way to reach a
// forward-declared parameter.
@OptIn(ExperimentalForeignApi::class)
internal actual fun AVAsynchronousCIImageFilteringRequest.finish(
  image: CIImage,
  context: CIContext?,
) {
  finishWithImage(
    image as objcnames.classes.CIImage,
    context as objcnames.classes.CIContext?,
  )
}
