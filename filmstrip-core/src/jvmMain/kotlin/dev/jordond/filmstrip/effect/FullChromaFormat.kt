package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The format with [pixelFormat]'s bit depth and alpha but a chroma sample for every pixel.
 *
 * Fails loudly on a format this has no equivalent for, the way `alphaPixelFormat` does, so a new output format shows
 * up as a build failure rather than a canvas a row short.
 */
@InternalFilmstripApi
public fun fullChromaFormat(pixelFormat: String): String =
  when (pixelFormat) {
    "yuv420p" -> "yuv444p"
    "yuva420p" -> "yuva444p"
    "yuv420p10le", "p010le" -> "yuv444p10le"
    "yuva420p10le" -> "yuva444p10le"
    else -> error("No 4:4:4 pixel format known for $pixelFormat.")
  }
