package dev.jordond.filmstrip.internal

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import kotlin.test.Test

@OptIn(ExperimentalForeignApi::class)
class CoreFoundationReadsAppleTest {
  @Test
  fun aNumberUnderTheKeyReadsAsThatInt() =
    withProperties { properties ->
      properties.intValue(kCGImagePropertyPixelWidth) shouldBe WIDTH
    }

  @Test
  fun aKeyTheDictionaryDoesNotHoldReadsAsNothing() =
    withProperties { properties ->
      properties.intValue(kCGImagePropertyOrientation).shouldBeNull()
    }

  // The value comes back as an untyped pointer, so a string read as a number would be garbage rather than an error.
  @Test
  fun aValueThatIsNotANumberReadsAsNothing() =
    withProperties { properties ->
      properties.intValue(kCGImagePropertyPixelHeight).shouldBeNull()
    }

  private fun withProperties(block: (CFDictionaryRef) -> Unit) {
    val properties =
      checkNotNull(
        CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr),
      )
    val width =
      memScoped {
        val raw = alloc<IntVar>().apply { value = WIDTH }
        checkNotNull(CFNumberCreate(null, kCFNumberIntType, raw.ptr))
      }
    val height = checkNotNull(CFStringCreateWithCString(null, "forty", kCFStringEncodingUTF8))
    try {
      CFDictionarySetValue(properties, kCGImagePropertyPixelWidth, width)
      CFDictionarySetValue(properties, kCGImagePropertyPixelHeight, height)
      block(properties)
    } finally {
      CFRelease(width)
      CFRelease(height)
      CFRelease(properties)
    }
  }

  private companion object {
    const val WIDTH = 64
  }
}
