package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.webcodecs.internal.COMPOSITOR_WRITES_TEN_BIT
import dev.jordond.filmstrip.webcodecs.internal.HDR_VP9_CODEC
import dev.jordond.filmstrip.webcodecs.internal.containerFor
import dev.jordond.filmstrip.webcodecs.internal.muxCodecKey
import dev.jordond.filmstrip.webcodecs.internal.webCodecString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The pure codec-string and container lookups [webCodecString], [muxCodecKey] and [containerFor]
 * drive, without a browser encoder or a wired-up pipeline behind them.
 */
class BrowserInteropTest {
  @Test
  fun `vp8 and av1 route to webm alongside vp9`() {
    assertEquals("webm", containerFor(VideoCodec.Vp8))
    assertEquals("webm", containerFor(VideoCodec.Vp9))
    assertEquals("webm", containerFor(VideoCodec.Av1))
  }

  @Test
  fun `h264 and hevc route to mp4`() {
    assertEquals("mp4", containerFor(VideoCodec.H264))
    assertEquals("mp4", containerFor(VideoCodec.Hevc))
  }

  @Test
  fun `a codec the ladder never encodes refuses a codec string rather than guessing one`() {
    assertFailsWith<IllegalStateException> { webCodecString(VideoCodec.Vp8, Size(1280, 720)) }
    assertFailsWith<IllegalStateException> { webCodecString(VideoCodec.Av1, Size(1280, 720)) }
  }

  // A profile carries the bit depth, so a grade that reaches the encoder has to change the string.
  // Handing back the 8-bit one would write an SDR stream into a file tagged BT.2020.
  @Test
  fun `an hdr vp9 encode names the ten-bit profile`() {
    assertEquals("vp09.00.10.08", webCodecString(VideoCodec.Vp9, Size(1280, 720)))
    assertEquals(HDR_VP9_CODEC, webCodecString(VideoCodec.Vp9, Size(1280, 720), hdr = true))
  }

  // The HDR ladder never picks either, so the 8-bit strings they resolve to can never carry a grade.
  @Test
  fun `a codec with no ten-bit browser encoder refuses an hdr string rather than lying`() {
    assertFailsWith<IllegalStateException> { webCodecString(VideoCodec.H264, Size(1280, 720), hdr = true) }
    assertFailsWith<IllegalStateException> { webCodecString(VideoCodec.Hevc, Size(1280, 720), hdr = true) }
  }

  @Test
  fun `a codec the ladder never encodes refuses a mux key rather than guessing one`() {
    assertFailsWith<IllegalStateException> { muxCodecKey(VideoCodec.Vp8) }
    assertFailsWith<IllegalStateException> { muxCodecKey(VideoCodec.Av1) }
  }

  // The colour effects lower to one WebGL pass that runs a matrix over the encoded signal, with no
  // arm for the display or scene gain a kept grade needs. That is only safe while this backend
  // cannot write a grade at all, so the day the compositor goes ten bit this fails rather than the
  // export quietly grading the wrong domain.
  @Test
  fun `the compositor still cannot write a grade`() {
    assertFalse(
      COMPOSITOR_WRITES_TEN_BIT,
      "the compositor writes ten bit now, so the colour matrix needs the lowering the other backends have",
    )
  }
}
