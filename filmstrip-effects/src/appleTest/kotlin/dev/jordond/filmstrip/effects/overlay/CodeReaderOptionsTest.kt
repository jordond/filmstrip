package dev.jordond.filmstrip.effects.overlay

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.kCIContextWorkingColorSpace
import platform.CoreImage.kCIContextWorkingFormat
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSDictionary
import platform.Foundation.NSNull
import platform.Foundation.NSNumber
import platform.Foundation.allKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The options the overlay code reader hands to Core Image arrive as a real dictionary holding both keys.
 *
 * A Kotlin map bridges into a dictionary that macOS reads as expected and iOS can read as holding nothing, so this
 * checks the dictionary itself rather than a render that would only show the difference on a device.
 */
@OptIn(ExperimentalForeignApi::class)
class CodeReaderOptionsTest {
  @Test
  fun `the code reader options are a Foundation dictionary`() {
    assertIs<NSDictionary>(codeReaderOptions())
  }

  @Test
  fun `the code reader options carry no working space and a float working format`() {
    val options = codeReaderOptions() as NSDictionary

    assertEquals(2uL, options.count)
    assertIs<NSNull>(options.objectForKey(checkNotNull(kCIContextWorkingColorSpace)))
    val format = assertIs<NSNumber>(options.objectForKey(checkNotNull(kCIContextWorkingFormat)))
    assertEquals(kCIFormatRGBAf, format.intValue)
  }

  @Test
  fun `the code reader options are keyed by strings`() {
    val keys = (codeReaderOptions() as NSDictionary).allKeys

    assertTrue(keys.isNotEmpty(), "an empty dictionary proves nothing")
    keys.forEach { key ->
      assertTrue(key is String, "$key is a ${key?.let { it::class.simpleName }}, not a string")
    }
  }
}
