package dev.jordond.filmstrip.effects.overlay

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.kCIContextUseSoftwareRenderer
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The options a Core Image context is made with arrive as a real dictionary keyed by strings, holding one key for each
 * option asked for. That covers the overlay code reader's and the ones the Apple tests build their contexts with.
 *
 * A Kotlin map bridges into a dictionary that macOS reads as expected and iOS can read as holding nothing, so this
 * checks the dictionary itself rather than a render that would only show the difference on a device.
 */
@OptIn(ExperimentalForeignApi::class)
class ContextOptionsTest {
  @Test
  fun `the code reader options carry no working space and a float working format`() {
    val options = assertIs<NSDictionary>(contextOptions(unmanaged = true, floatFormat = true))

    assertEquals(2uL, options.count)
    assertIs<NSNull>(options.objectForKey(checkNotNull(kCIContextWorkingColorSpace)))
    val format = assertIs<NSNumber>(options.objectForKey(checkNotNull(kCIContextWorkingFormat)))
    assertEquals(kCIFormatRGBAf, format.intValue)
  }

  @Test
  fun `a float working format alone keeps the default working space`() {
    val options = assertIs<NSDictionary>(contextOptions(floatFormat = true))

    assertEquals(1uL, options.count)
    assertNull(options.objectForKey(checkNotNull(kCIContextWorkingColorSpace)))
    val format = assertIs<NSNumber>(options.objectForKey(checkNotNull(kCIContextWorkingFormat)))
    assertEquals(kCIFormatRGBAf, format.intValue)
  }

  @Test
  fun `the software renderer is asked for as true`() {
    val options = assertIs<NSDictionary>(contextOptions(softwareRenderer = true))

    val software = assertIs<NSNumber>(options.objectForKey(checkNotNull(kCIContextUseSoftwareRenderer)))
    assertTrue(software.boolValue, "the software renderer is asked for as $software")
  }

  @Test
  fun `every option set is a string keyed Foundation dictionary with one key per option`() {
    val flags = listOf(false, true)
    flags.forEach { unmanaged ->
      flags.forEach { floatFormat ->
        flags.forEach { software ->
          val name = "unmanaged $unmanaged float $floatFormat software $software"
          val options = assertIs<NSDictionary>(contextOptions(unmanaged, floatFormat, software), name)

          assertEquals(listOf(unmanaged, floatFormat, software).count { it }.toULong(), options.count, name)
          options.allKeys.forEach { key ->
            assertTrue(key is String, "$name has $key, a ${key?.let { it::class.simpleName }} and not a string")
          }
        }
      }
    }
  }
}
