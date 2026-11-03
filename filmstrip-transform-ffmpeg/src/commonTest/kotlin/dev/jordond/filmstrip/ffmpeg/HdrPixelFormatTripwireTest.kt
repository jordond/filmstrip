package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.lowering.FFMPEG_ENCODERS
import dev.jordond.filmstrip.ffmpeg.internal.lowering.alphaPixelFormat
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Pins the assumption that every encoder's HDR pixel format has an alpha-carrying equivalent.
 *
 * `alphaPixelFormat` fails loudly on a format it cannot spell, which is only a safe default while
 * every format already in [FFMPEG_ENCODERS] is one it recognises. Walking the table here is what
 * turns a new HDR encoder landing without a matching case into a build failure here rather than a
 * failed export the day someone's composition happens to defer a fill.
 */
class HdrPixelFormatTripwireTest {
  // Every ten bit format the table writes HDR in widens to the one ten bit alpha format, so the
  // overlay that defers a fill keeps all ten bits of the grade.
  @Test
  fun `every encoder's hdrPixelFormat has a ten bit alpha spelling`() {
    val formats =
      FFMPEG_ENCODERS.values
        .flatten()
        .mapNotNull { it.hdrPixelFormat }
        .distinct()

    formats.isNotEmpty() shouldBe true
    formats.associateWith(::alphaPixelFormat) shouldBe formats.associateWith { TEN_BIT_ALPHA }
  }

  @Test
  fun `an sdr frame widens to eight bit alpha`() {
    alphaPixelFormat("yuv420p") shouldBe "yuva420p"
  }

  @Test
  fun `a format with no alpha spelling fails rather than guessing`() {
    assertFailsWith<IllegalStateException> { alphaPixelFormat("nv12") }
  }

  private companion object {
    const val TEN_BIT_ALPHA = "yuva420p10le"
  }
}
