package dev.jordond.filmstrip.effects

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreImage.CIImage

/**
 * Moves the image so its extent starts at the coordinate origin.
 *
 * Core Image transforms about the origin, so a placement applied to an image whose extent sits
 * somewhere else lands somewhere else too.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.atOrigin(): CIImage =
  extent.useContents {
    if (origin.x == 0.0 && origin.y == 0.0) {
      this@atOrigin
    } else {
      this@atOrigin.imageByApplyingTransform(CGAffineTransformMakeTranslation(-origin.x, -origin.y))
    }
  }
