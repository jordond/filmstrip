package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import platform.AVFoundation.AVAsynchronousCIImageFilteringRequest
import platform.CoreImage.CIImage

// The forward-declared class iOS types these members as carries no members of its own. The pointer
// under it is the same object, so both directions are a reinterpret and nothing else.

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun AVAsynchronousCIImageFilteringRequest.sourceFrame(): CIImage =
  interpretObjCPointer(sourceImage.objcPtr())

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun AVAsynchronousCIImageFilteringRequest.finishWithFrame(image: CIImage) {
  finishWithImage(interpretObjCPointer(image.objcPtr()), context = null)
}
