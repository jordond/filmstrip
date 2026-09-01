package dev.jordond.filmstrip.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFNumberIntType

/**
 * Reads [key] off this dictionary as an [Int], or null when it holds no number.
 *
 * A CoreFoundation dictionary hands back an untyped pointer, so the type is checked before the
 * pointer is reinterpreted.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CFDictionaryRef.intValue(key: CFStringRef?): Int? {
  val value = CFDictionaryGetValue(this, key) ?: return null
  if (CFGetTypeID(value) != CFNumberGetTypeID()) return null

  return memScoped {
    val number = alloc<IntVar>()
    if (CFNumberGetValue(value.reinterpret(), kCFNumberIntType, number.ptr)) number.value else null
  }
}
