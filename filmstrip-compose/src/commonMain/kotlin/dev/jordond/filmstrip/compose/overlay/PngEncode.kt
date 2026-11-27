package dev.jordond.filmstrip.compose.overlay

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Encodes this bitmap as a PNG, keeping its alpha.
 *
 * Runs on whatever thread calls it, which a capture keeps off the main thread.
 */
internal expect fun ImageBitmap.encodePng(): ByteArray
