package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effects.overlay.codeReader
import dev.jordond.filmstrip.effects.overlay.contextOptions
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.CIContext
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * A Core Image context to read pixels through, made with Core Image's default working format or a float one when
 * [floatFormat] is set, failing the calling test when this process builds none.
 *
 * An Objective-C `init` that answers nil reaches Kotlin as a non-null reference that is not there, and Kotlin/Native
 * raises at the constructor, not at the first use. The software renderer is tried when a GPU context is not built,
 * and the failure names why each one was not.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun renderingContext(floatFormat: Boolean = false): CIContext {
  val failures = mutableListOf<String>()

  fun built(software: Boolean): CIContext? =
    try {
      CIContext.contextWithOptions(contextOptions(floatFormat = floatFormat, softwareRenderer = software))
    } catch (absent: NullPointerException) {
      failures += "${if (software) "the software" else "the GPU"} context: $absent"
      null
    }

  val format = if (floatFormat) "a float" else "the default"
  return built(software = false) ?: built(software = true)
    ?: fail("this process cannot build a Core Image context with $format working format, ${failures.joinToString()}")
}

/**
 * The overlay raster's own [codeReader], failing the calling test when none was built and so no stored samples read
 * back.
 */
internal fun rasterReader(): CIContext =
  assertNotNull(codeReader, "the overlay raster built no code reader, so it reads no samples back")

/**
 * Fails the calling test unless the overlay raster built its [codeReader], for a case that reads samples through the
 * raster and never the reader itself.
 */
internal fun assertRasterReads() {
  rasterReader()
}
