package dev.jordond.filmstrip.compose

import androidx.compose.ui.graphics.ImageBitmap
import dev.jordond.filmstrip.media.PlatformImage

/**
 * Converts a frame into a Compose [ImageBitmap] ready to draw.
 *
 * The pixels still belong to the [PlatformImage]. Android hands back the image's own bitmap and every other platform
 * copies it, so keep the image open for as long as the bitmap is drawn and close it after.
 *
 * @return The bitmap, or null once the image is closed or when it has no pixels.
 */
public expect fun PlatformImage.toImageBitmap(): ImageBitmap?
