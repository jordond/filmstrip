package dev.jordond.filmstrip.compose

import androidx.compose.ui.graphics.ImageBitmap
import dev.jordond.filmstrip.player.ReadbackFrame

/**
 * Converts a readback frame into a Compose [ImageBitmap] ready to draw.
 *
 * The pixels are always copied. Returns null when the frame reports a non-positive size.
 */
internal expect fun ReadbackFrame.toImageBitmap(): ImageBitmap?
