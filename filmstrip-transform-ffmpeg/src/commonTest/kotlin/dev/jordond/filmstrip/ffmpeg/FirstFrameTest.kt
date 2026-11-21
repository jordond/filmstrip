package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.probe.parseFirstFrameDamaged
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Reading the frame count ffprobe prints after decoding a source's first video packets.
 */
class FirstFrameTest {
  @Test
  fun aDecodedFrameIsWhole() {
    parseFirstFrameDamaged("3\n", "") shouldBe false
  }

  @Test
  fun noFramesAreDamaged() {
    parseFirstFrameDamaged("0\n", "[h264 @ 0x1] no frame!\n") shouldBe true
  }

  @Test
  fun anEmptyAnswerIsDamaged() {
    parseFirstFrameDamaged("", "") shouldBe true
  }

  @Test
  fun aCodecWithNoDecoderInThisBuildIsWhole() {
    parseFirstFrameDamaged("0\n", "Unsupported codec with id 225 for input stream 0\n") shouldBe false
  }
}
