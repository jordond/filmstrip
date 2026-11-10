package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.media.ColorSpace
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.CIImage

/**
 * The space an SDR frame's BT.709 codes are named in, held for the life of the process.
 */
@OptIn(ExperimentalForeignApi::class)
internal val BT709_CODE_SPACE by lazy { ColorSpace.Bt709.createCodeSpace() }

/**
 * Runs [blend] on this image and [other] as the codes they store rather than as the light they stand for.
 *
 * Core Image blends in its linear working space, which puts a half transparent pixel at a different code than the
 * stored code blend the other backends draw. The two images are brought back to codes first, and the result is named
 * as BT.709 codes again after.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.blendedInCodes(
  other: CIImage,
  blend: CIImage.(CIImage) -> CIImage,
): CIImage {
  val space = BT709_CODE_SPACE ?: error(NO_CODE_SPACE)
  val codes = imageByColorMatchingWorkingSpaceToColorSpace(space) ?: error(NO_CODE_SPACE)
  val otherCodes = other.imageByColorMatchingWorkingSpaceToColorSpace(space) ?: error(NO_CODE_SPACE)
  return codes.blend(otherCodes).imageByColorMatchingColorSpaceToWorkingSpace(space) ?: error(NO_CODE_SPACE)
}

private const val NO_CODE_SPACE = "Core Image would not move a frame between BT.709 codes and its working space."
