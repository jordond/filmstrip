package dev.jordond.filmstrip.avfoundation.internal

import platform.AVFoundation.AVAsynchronousCIImageFilteringRequest
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage

/**
 * The frame this request wants filtered.
 *
 * AVFoundation's headers forward-declare `CIImage` on iOS and import it on macOS, so cinterop types
 * the same property as two unrelated classes depending on the target. The objects are the same at
 * runtime and only the declaration differs, so each target casts instead of converting.
 */
internal expect fun AVAsynchronousCIImageFilteringRequest.sourceFrame(): CIImage

/**
 * Hands [image] back to AVFoundation, rendered through [context].
 *
 * The same per-target typing as [sourceFrame] applies. A null context leaves AVFoundation to render
 * through one of its own.
 */
internal expect fun AVAsynchronousCIImageFilteringRequest.finish(
  image: CIImage,
  context: CIContext?,
)
