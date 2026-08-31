package dev.jordond.filmstrip.compose

import androidx.compose.ui.graphics.ImageBitmap
import dev.jordond.filmstrip.media.PlatformImage

/**
 * Converts a rendered frame into a Compose [ImageBitmap] ready to draw.
 *
 * The frame still belongs to the [PlatformImage]. Where the platform can share the pixels rather
 * than copy them, closing the image leaves the bitmap unusable.
 *
 * Returns null once the image has been closed.
 */
internal expect fun PlatformImage.toImageBitmap(): ImageBitmap?
