package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.probe.FirstFrame
import dev.jordond.filmstrip.ffmpeg.internal.probe.parseFirstFrame
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Reading the frame count ffprobe prints after decoding a source's first video packets.
 */
class FirstFrameTest {
  @Test
  fun aDecodedFrameIsWhole() {
    parseFirstFrame("3\n", "") shouldBe FirstFrame.Decodes
  }

  @Test
  fun noFramesAreDamaged() {
    parseFirstFrame("0\n", "[h264 @ 0x1] no frame!\n") shouldBe FirstFrame.Damaged
  }

  @Test
  fun anEmptyAnswerIsDamaged() {
    parseFirstFrame("", "") shouldBe FirstFrame.Damaged
  }

  @Test
  fun aCodecWithNoDecoderInThisBuildHasNoDecoder() {
    parseFirstFrame("0\n", "Unsupported codec with id 225 for input stream 0\n") shouldBe FirstFrame.NoDecoder
  }
}
