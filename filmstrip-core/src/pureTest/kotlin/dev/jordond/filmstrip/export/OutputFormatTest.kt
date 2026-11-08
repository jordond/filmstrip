package dev.jordond.filmstrip.export

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class OutputFormatTest {
  @Test
  fun `an output frame rate of zero or less is refused`() {
    listOf(0, -1).forEach { rate ->
      assertFailsWith<IllegalArgumentException> { format(rate) }
    }
  }

  @Test
  fun `an output frame rate above zero is kept`() {
    format(1).frameRate shouldBe 1
    format(30).frameRate shouldBe 30
  }

  private fun format(frameRate: Int) =
    OutputFormat(Size(1280, 720), VideoCodec.H264, AudioCodec.Aac, null, frameRate, null)
}
