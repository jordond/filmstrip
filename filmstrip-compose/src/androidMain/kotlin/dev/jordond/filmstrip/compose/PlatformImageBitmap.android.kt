package dev.jordond.filmstrip.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.jordond.filmstrip.media.PlatformImage

// The result shares the image's bitmap. Closing the image recycles it, so nothing may draw the
// result afterwards.
public actual fun PlatformImage.toImageBitmap(): ImageBitmap? = asBitmap()?.asImageBitmap()
