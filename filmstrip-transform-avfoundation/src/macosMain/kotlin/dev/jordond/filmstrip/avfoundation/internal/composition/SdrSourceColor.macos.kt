package dev.jordond.filmstrip.avfoundation.internal.composition

import platform.CoreVideo.kCVPixelFormatType_32BGRA

internal actual val relabelsBt601AsBt709: Boolean = true

internal actual val hasPixelTransferSession: Boolean = true

internal actual val sdrTransferFormat: UInt = kCVPixelFormatType_32BGRA
