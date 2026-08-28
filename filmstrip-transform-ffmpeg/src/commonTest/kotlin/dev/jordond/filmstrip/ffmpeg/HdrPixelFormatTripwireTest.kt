package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.FFMPEG_ENCODERS
import dev.jordond.filmstrip.ffmpeg.internal.alphaPixelFormat
import kotlin.test.Test

/**
 * Pins the assumption that every encoder's HDR pixel format has an alpha-carrying equivalent.
 *
 * `alphaPixelFormat` fails loudly on a format it cannot spell, which is only a safe default while
 * every format already in [FFMPEG_ENCODERS] is one it recognises. Walking the table here is what
 * turns a new HDR encoder landing without a matching case into a build failure here rather than a
 * failed export the day someone's composition happens to defer a fill.
 */
class HdrPixelFormatTripwireTest {
  @Test
  fun `every encoder's hdrPixelFormat has an alpha spelling`() {
    FFMPEG_ENCODERS.values.flatten().mapNotNull { it.hdrPixelFormat }.distinct().forEach { pixelFormat ->
      alphaPixelFormat(pixelFormat)
    }
  }
}
