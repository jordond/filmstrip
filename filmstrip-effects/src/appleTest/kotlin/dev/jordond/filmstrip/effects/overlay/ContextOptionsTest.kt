package dev.jordond.filmstrip.effects.overlay

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
    val options = contextOptions(unmanaged = true, floatFormat = true).shouldBeObjC<NSDictionary>()

    options.count shouldBe 2uL
    options.objectForKey(checkNotNull(kCIContextWorkingColorSpace)).shouldBeObjC<NSNull>()
    val format = options.objectForKey(checkNotNull(kCIContextWorkingFormat)).shouldBeObjC<NSNumber>()
    format.intValue shouldBe kCIFormatRGBAf
  }

  @Test
  fun `a float working format alone keeps the default working space`() {
    val options = contextOptions(floatFormat = true).shouldBeObjC<NSDictionary>()

    options.count shouldBe 1uL
    options.objectForKey(checkNotNull(kCIContextWorkingColorSpace)).shouldBeNull()
    val format = options.objectForKey(checkNotNull(kCIContextWorkingFormat)).shouldBeObjC<NSNumber>()
    format.intValue shouldBe kCIFormatRGBAf
  }

  @Test
  fun `the software renderer is asked for as true`() {
    val options = contextOptions(softwareRenderer = true).shouldBeObjC<NSDictionary>()

    val software = options.objectForKey(checkNotNull(kCIContextUseSoftwareRenderer)).shouldBeObjC<NSNumber>()
    withClue("the software renderer is asked for as $software") { software.boolValue shouldBe true }
  }

  @Test
  fun `every option set is a string keyed Foundation dictionary with one key per option`() {
    val flags = listOf(false, true)
    flags.forEach { unmanaged ->
      flags.forEach { floatFormat ->
        flags.forEach { software ->
          val name = "unmanaged $unmanaged float $floatFormat software $software"
          val options =
            withClue(name) {
              contextOptions(unmanaged, floatFormat, software).shouldBeObjC<NSDictionary>()
            }

          withClue(name) { options.count shouldBe listOf(unmanaged, floatFormat, software).count { it }.toULong() }
          options.allKeys.forEach { key ->
            withClue("$name has $key, a ${key?.let { it::class.simpleName }} and not a string") {
              (key is String) shouldBe true
            }
          }
        }
      }
    }
  }
}
