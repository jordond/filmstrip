package dev.jordond.filmstrip.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFBooleanGetTypeID
import platform.CoreFoundation.CFBooleanGetValue
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

/**
 * Reads [key] off this dictionary as a [Boolean], or false when it holds no boolean.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CFDictionaryRef.boolValue(key: CFStringRef?): Boolean {
  val value = CFDictionaryGetValue(this, key) ?: return false
  if (CFGetTypeID(value) != CFBooleanGetTypeID()) return false

  return CFBooleanGetValue(value.reinterpret())
}
