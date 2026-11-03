package dev.jordond.filmstrip.effects.overlay

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.CIContext
import platform.CoreImage.kCIContextUseSoftwareRenderer
import platform.CoreImage.kCIContextWorkingColorSpace
import platform.CoreImage.kCIContextWorkingFormat
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNull
import platform.Foundation.NSNumber
import platform.Foundation.numberWithBool
import platform.Foundation.numberWithInt
import platform.Foundation.setValue

/**
 * The context the overlay raster reads stored samples back through, built once with [contextOptions] for no working
 * space and a float working format, or null when none can be built.
 *
 * Kotlin/Native raises at the constructor when Core Image answers nil, which a headless process can. The software
 * renderer is tried next, and when neither builds the samples are not read back at all.
 */
@OptIn(ExperimentalForeignApi::class)
internal val codeReader: CIContext? by lazy {
  contextOrNull(contextOptions(unmanaged = true, floatFormat = true))
    ?: contextOrNull(contextOptions(unmanaged = true, floatFormat = true, softwareRenderer = true))
}

@OptIn(ExperimentalForeignApi::class)
private fun contextOrNull(options: Map<Any?, *>): CIContext? =
  try {
    CIContext.contextWithOptions(options)
  } catch (absent: NullPointerException) {
    null
  }

/**
 * The options a Core Image context is made with. [unmanaged] sets no working space, [floatFormat] a float working
 * format and [softwareRenderer] the software renderer, and each one left unset leaves Core Image's default.
 *
 * Built as an `NSMutableDictionary` and handed over as the dictionary itself, so Core Image reads exactly the keys it
 * was given.
 */
@Suppress("CAST_NEVER_SUCCEEDS", "UNCHECKED_CAST")
@OptIn(ExperimentalForeignApi::class)
internal fun contextOptions(
  unmanaged: Boolean = false,
  floatFormat: Boolean = false,
  softwareRenderer: Boolean = false,
): Map<Any?, *> {
  val options = NSMutableDictionary()
  if (unmanaged) kCIContextWorkingColorSpace?.let { options.setValue(NSNull.`null`(), forKey = it) }
  if (floatFormat) {
    kCIContextWorkingFormat?.let { options.setValue(NSNumber.numberWithInt(kCIFormatRGBAf), forKey = it) }
  }
  if (softwareRenderer) {
    kCIContextUseSoftwareRenderer?.let { options.setValue(NSNumber.numberWithBool(true), forKey = it) }
  }
  return options as Map<Any?, *>
}
