package dev.jordond.filmstrip.avfoundation.internal.composition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo

internal actual val relabelsBt601AsBt709: Boolean = false

internal actual val sdrTransferFormat: UInt = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange

@OptIn(ExperimentalForeignApi::class)
internal actual val hasPixelTransferSession: Boolean =
  NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
    cValue<NSOperatingSystemVersion> { majorVersion = PIXEL_TRANSFER_MAJOR_VERSION },
  )

private const val PIXEL_TRANSFER_MAJOR_VERSION = 16L
