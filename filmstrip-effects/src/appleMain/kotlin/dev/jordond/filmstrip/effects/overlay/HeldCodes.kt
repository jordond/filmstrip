package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.picture.PictureCrop
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRetain
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import platform.CoreImage.kCIImageColorSpace
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNull
import platform.Foundation.setValue
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import platform.ImageIO.CGImageSourceRef
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner

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
 * @property image The codes, with no color space attached.
 * @property bytes What the bitmap behind [image] holds, or for a Core Image recipe the decode ImageIO makes of it. It
 * is null when that count cannot be made, and a draw that makes floats from [image] then refuses it as too large.
 * @property bitmap The bitmap [image] is made from, or null for a Core Image recipe.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class HeldCodes(
  val image: CIImage,
  val bytes: Long?,
  val bitmap: CGImageRef? = null,
) {
  // The bitmap is retained for as long as these codes live, so a cell can be cut from it after the decode is let go.
  private val retained: Cleaner? =
    bitmap?.let { held ->
      CGImageRetain(held)
      createCleaner(held, ::CGImageRelease)
    }
}

/**
 * The texels [crop] names of these codes, as codes of their own, or null when the codes are not backed by a bitmap.
 *
 * The cut is made on the bitmap and never on the Core Image recipe, so no texel beside the rectangle can bleed into
 * it however the result is scaled. The new bitmap is a view onto the bytes this one holds, so it takes no more memory
 * and its [bytes] stay those of the whole.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun HeldCodes.cutTo(crop: PictureCrop): HeldCodes? {
  val bitmap = bitmap ?: return null
  val (width, height) = crop.size.width.toDouble() to crop.size.height.toDouble()
  val rect = CGRectMake(crop.left.toDouble(), crop.top.toDouble(), width, height)
  val cut = CGImageCreateWithImageInRect(bitmap, rect) ?: return null
  val codes = HeldCodes(CIImage.imageWithCGImage(cut, unmanagedOptions()), bytes, cut)
  CGImageRelease(cut)
  return codes
}

/**
 * This bitmap as Core Image codes with no color space, holding its row bytes times its height. The caller releases
 * the bitmap.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CGImageRef.heldCodes(): HeldCodes =
  HeldCodes(
    CIImage.imageWithCGImage(this, unmanagedOptions()),
    bytesOf(CGImageGetBytesPerRow(this).toLong(), CGImageGetHeight(this).toLong()),
    this,
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

/**
 * The options an image is decoded with, an `NSMutableDictionary` holding [kCIImageColorSpace] mapped to `NSNull`.
 */
@Suppress("CAST_NEVER_SUCCEEDS", "UNCHECKED_CAST")
@OptIn(ExperimentalForeignApi::class)
internal fun unmanagedOptions(): Map<Any?, *> {
  val options = NSMutableDictionary()
  kCIImageColorSpace?.let { options.setValue(NSNull.`null`(), forKey = it) }
  return options as Map<Any?, *>
}
