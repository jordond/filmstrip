package dev.jordond.filmstrip.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.jordond.filmstrip.media.PlatformImage

// The bitmap is shared rather than copied, which is what makes a strip affordable here. Closing the
// image recycles it, so nothing may draw the result afterwards.
internal actual fun PlatformImage.toImageBitmap(): ImageBitmap? = asBitmap()?.asImageBitmap()
