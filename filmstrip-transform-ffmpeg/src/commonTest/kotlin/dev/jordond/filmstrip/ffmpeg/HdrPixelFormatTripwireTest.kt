package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.lowering.FFMPEG_ENCODERS
import dev.jordond.filmstrip.ffmpeg.internal.lowering.alphaPixelFormat
import dev.jordond.filmstrip.ffmpeg.internal.lowering.fullChromaFormat
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Pins the assumption that every encoder's HDR pixel format has an alpha-carrying and a 4:4:4 equivalent.
 *
 * `alphaPixelFormat` and `fullChromaFormat` fail loudly on a format they cannot spell, which is only a safe default
 * while every format already in [FFMPEG_ENCODERS] is one they recognise. Walking the table here is what turns a new HDR
 * encoder landing without a matching case into a build failure here rather than a failed export the day someone's
 * composition happens to defer a fill or build an odd canvas.
 */
class HdrPixelFormatTripwireTest {
  // Every ten bit format the table writes HDR in widens to the one ten bit alpha format, so the
  // overlay that defers a fill keeps all ten bits of the grade.
  @Test
  fun `every encoder's hdrPixelFormat has a ten bit alpha spelling`() {
    val formats = hdrFormats()

    formats.isNotEmpty() shouldBe true
    formats.associateWith(::alphaPixelFormat) shouldBe formats.associateWith { TEN_BIT_ALPHA }
  }

  // An odd canvas is built in 4:4:4 at the depth and alpha of the tail it ends on, which is the HDR format itself or
  // its alpha spelling while a fill is deferred, so both keep all ten bits.
  @Test
  fun `every encoder's hdrPixelFormat has a ten bit 4-4-4 spelling with and without alpha`() {
    val formats = hdrFormats()

    formats.isNotEmpty() shouldBe true
    formats.associateWith(::fullChromaFormat) shouldBe formats.associateWith { TEN_BIT_FULL }
    formats.associateWith { fullChromaFormat(alphaPixelFormat(it)) } shouldBe
      formats.associateWith { TEN_BIT_FULL_ALPHA }
  }

  @Test
  fun `an sdr frame widens to eight bit alpha`() {
    alphaPixelFormat("yuv420p") shouldBe "yuva420p"
  }

  @Test
  fun `a format with no alpha spelling fails rather than guessing`() {
    assertFailsWith<IllegalStateException> { alphaPixelFormat("nv12") }
  }

  @Test
  fun `a format with no 4-4-4 spelling fails rather than guessing`() {
    assertFailsWith<IllegalStateException> { fullChromaFormat("nv12") }
  }

  private fun hdrFormats(): List<String> =
    FFMPEG_ENCODERS.values
      .flatten()
      .mapNotNull { it.hdrPixelFormat }
      .distinct()

  private companion object {
    const val TEN_BIT_ALPHA = "yuva420p10le"
    const val TEN_BIT_FULL = "yuv444p10le"
    const val TEN_BIT_FULL_ALPHA = "yuva444p10le"
  }
}
