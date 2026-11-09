package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.webcodecs.internal.interop.HDR_VP9_CODEC
import dev.jordond.filmstrip.webcodecs.internal.interop.containerCarries
import dev.jordond.filmstrip.webcodecs.internal.interop.containerFor
import dev.jordond.filmstrip.webcodecs.internal.interop.fileExtensionOf
import dev.jordond.filmstrip.webcodecs.internal.interop.muxAudioCodecKey
import dev.jordond.filmstrip.webcodecs.internal.interop.muxCodecKey
import dev.jordond.filmstrip.webcodecs.internal.interop.webCodecString
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The pure codec-string and container lookups [webCodecString], [muxCodecKey], [muxAudioCodecKey], [containerFor],
 * [containerCarries] and [fileExtensionOf] drive, without a browser encoder or a wired-up pipeline behind them.
 */
class BrowserInteropTest {
  @Test
  fun `vp8 and av1 route to webm alongside vp9`() {
    containerFor(VideoCodec.Vp8) shouldBe "webm"
    containerFor(VideoCodec.Vp9) shouldBe "webm"
    containerFor(VideoCodec.Av1) shouldBe "webm"
  }

  @Test
  fun `h264 and hevc route to mp4`() {
    containerFor(VideoCodec.H264) shouldBe "mp4"
    containerFor(VideoCodec.Hevc) shouldBe "mp4"
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
    webCodecString(VideoCodec.Vp9, Size(1280, 720)) shouldBe "vp09.00.10.08"
    webCodecString(VideoCodec.Vp9, Size(1280, 720), hdr = true) shouldBe HDR_VP9_CODEC
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

  // Both containers carry a complete color tag, but mediabunny rewrites the WebM VP9 frame
  // header's color space bits from a map with no BT.2020 entry and writes zero there. The mp4
  // muxer leaves the encoder's own header alone, so a grade goes in mp4 whatever SDR VP9 does.
  @Test
  fun `an hdr vp9 encode routes to mp4 where an sdr one stays in webm`() {
    containerFor(VideoCodec.Vp9, hdr = false) shouldBe "webm"
    containerFor(VideoCodec.Vp9, hdr = true) shouldBe "mp4"
  }

  @Test
  fun `a file with no video track follows its audio`() {
    containerFor(null, AudioCodec.Opus) shouldBe "webm"
    containerFor(null, AudioCodec.Aac) shouldBe "mp4"
    containerFor(null) shouldBe "mp4"
  }

  // mediabunny's WebM muxer throws on AAC, and mp4 takes both codecs this backend encodes.
  @Test
  fun `webm carries opus and not aac where mp4 carries both`() {
    containerCarries(VideoCodec.Vp9, AudioCodec.Aac, hdr = false) shouldBe false
    containerCarries(VideoCodec.Vp9, AudioCodec.Opus, hdr = false) shouldBe true
    containerCarries(VideoCodec.Vp9, AudioCodec.None, hdr = false) shouldBe true
    containerCarries(VideoCodec.H264, AudioCodec.Aac, hdr = false) shouldBe true
    containerCarries(VideoCodec.H264, AudioCodec.Opus, hdr = false) shouldBe true
    containerCarries(null, AudioCodec.Aac, hdr = false) shouldBe true
  }

  // An HDR VP9 encode goes in mp4, so it keeps AAC an SDR one has to give up.
  @Test
  fun `an hdr vp9 encode keeps aac in mp4`() {
    containerCarries(VideoCodec.Vp9, AudioCodec.Aac, hdr = true) shouldBe true
  }

  @Test
  fun `an mp4 with no video track is named m4a`() {
    fileExtensionOf("mp4", writesVideo = false) shouldBe "m4a"
    fileExtensionOf("mp4", writesVideo = true) shouldBe "mp4"
    fileExtensionOf("webm", writesVideo = false) shouldBe "webm"
    fileExtensionOf("webm", writesVideo = true) shouldBe "webm"
  }

  @Test
  fun `each browser audio codec has its mediabunny key`() {
    muxAudioCodecKey(AudioCodec.Aac) shouldBe "aac"
    muxAudioCodecKey(AudioCodec.Opus) shouldBe "opus"
    assertFailsWith<IllegalStateException> { muxAudioCodecKey(AudioCodec.Flac) }
  }
}
