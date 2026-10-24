package dev.jordond.filmstrip.effects.overlay

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIImage
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import platform.ImageIO.CGImageSourceRef

/**
 * The bytes a read or a draw of an overlay picture holds at its peak, or null when that count does not fit in a [Long]
 * or any part of it is null or negative.
 *
 * Every read and draw on Apple is counted this way against [PICTURE_PEAK_CEILING]. [held] is the decode the step
 * works from, which stays held the whole time. [band] is what the step holds on the way, a copy of the samples or one
 * band of rows. [made] is what the step writes. The three are held together at the peak, so the count is their sum.
 */
internal fun picturePeakBytes(
  held: Long?,
  band: Long?,
  made: Long?,
): Long? {
  if (held == null || band == null || made == null) return null
  if (held < 0 || band < 0 || made < 0) return null
  if (held > Long.MAX_VALUE - band || held + band > Long.MAX_VALUE - made) return null
  return held + band + made
}

/**
 * The bytes [rows] rows of [rowBytes] bytes each take, or null when either is negative or the count does not fit in a
 * [Long].
 */
internal fun bytesOf(
  rowBytes: Long,
  rows: Long,
): Long? {
  if (rowBytes < 0 || rows < 0) return null
  if (rows != 0L && rowBytes > Long.MAX_VALUE / rows) return null
  return rowBytes * rows
}

/**
 * An overlay picture's codes as Core Image draws them, and the bytes held behind them while they are drawn.
 *
 * @property image The codes, with no colour space attached.
 * @property bytes What the bitmap behind [image] holds, or for a Core Image recipe the decode ImageIO makes of it. It
 * is null when that count cannot be made, and a draw that makes floats from [image] then refuses it as too large.
 */
internal class HeldCodes(
  val image: CIImage,
  val bytes: Long?,
)

/**
 * This bitmap as Core Image codes with no colour space, holding its row bytes times its height. The caller releases
 * the bitmap.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CGImageRef.heldCodes(): HeldCodes =
  HeldCodes(
    CIImage.imageWithCGImage(this, unmanagedOptions()),
    bytesOf(CGImageGetBytesPerRow(this).toLong(), CGImageGetHeight(this).toLong()),
  )

/**
 * The bytes ImageIO decodes this source's primary picture into, its row bytes times its height, or null when it holds
 * no picture or the count does not fit in a [Long]. Only the picture's header is read.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CGImageSourceRef.decodedBytes(): Long? {
  val image = CGImageSourceCreateImageAtIndex(this, CGImageSourceGetPrimaryImageIndex(this), null) ?: return null
  val bytes = bytesOf(CGImageGetBytesPerRow(image).toLong(), CGImageGetHeight(image).toLong())
  CGImageRelease(image)
  return bytes
}
