package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.ffmpeg.internal.picture.MAX_HEADER
import dev.jordond.filmstrip.ffmpeg.internal.picture.MAX_PAM_FIELDS
import dev.jordond.filmstrip.ffmpeg.internal.picture.RunFrame
import dev.jordond.filmstrip.ffmpeg.internal.picture.pamFrames
import dev.jordond.filmstrip.ffmpeg.internal.picture.y4mFrames
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test

/**
 * How the frames a run child writes are read back, and where a stream that is not one a child writes is refused.
 */
class PictureFramesTest {
  @Test
  fun `a y4m header line one short of the longest reads and the longest does not`() {
    val header = { length: Int -> "YUV4MPEG2 W2 H2 C444 X".padEnd(length, 'x') }
    y4m(header(MAX_HEADER - 1), frame(TEXELS * 3)).frameCount() shouldBe 1
    y4m(header(MAX_HEADER), frame(TEXELS * 3)).frameCount().shouldBeNull()
  }

  @Test
  fun `a pam header of the most fields reads and one more does not`() {
    val fields = { count: Int -> List(count - REQUIRED_FIELDS) { "EXTRA$it 1" } }
    pam(fields(MAX_PAM_FIELDS)).frameCount() shouldBe 1
    pam(fields(MAX_PAM_FIELDS + 1)).frameCount().shouldBeNull()
  }

  @Test
  fun `a frame that ends part way does not read`() {
    y4m("YUV4MPEG2 W2 H2 C444", frame(TEXELS * 3 - 1)).frameCount().shouldBeNull()
    pam(emptyList(), TEXELS * 3 - 1).frameCount().shouldBeNull()
  }

  @Test
  fun `a pam frame turned down is never read past its header`() {
    val frames = pam(emptyList())
    val asked = mutableListOf<Pair<Size, Long>>()
    val admits = { size: Size, bytes: Long ->
      asked += size to bytes
      false
    }
    pamFrames(frames.stream, admits) { true } shouldBe 0
    asked shouldBe listOf(Size(2, 2) to TEXELS * 3L)
    frames.stream.available() shouldBe TEXELS * 3
  }

  // How many frames the stream reads as, every one admitted, or null when it does not read.
  private fun Frames.frameCount(): Int? = reader(stream, { _, _ -> true }) { true }

  private class Frames(
    val stream: InputStream,
    val reader: (InputStream, (Size, Long) -> Boolean, (RunFrame) -> Boolean) -> Int?,
  )

  private fun y4m(
    header: String,
    frame: ByteArray,
  ): Frames = Frames(ByteArrayInputStream("$header\n".encodeToByteArray() + frame), ::y4mFrames)

  private fun frame(bytes: Int): ByteArray = "FRAME\n".encodeToByteArray() + ByteArray(bytes)

  private fun pam(
    extra: List<String>,
    bytes: Int = TEXELS * 3,
  ): Frames {
    val fields = listOf("WIDTH 2", "HEIGHT 2", "DEPTH 3", "MAXVAL 255") + extra
    val header = (listOf("P7") + fields + "ENDHDR").joinToString("\n", postfix = "\n")
    return Frames(ByteArrayInputStream(header.encodeToByteArray() + ByteArray(bytes)), ::pamFrames)
  }

  private companion object {
    // Two by two texels.
    const val TEXELS = 4

    // WIDTH, HEIGHT, DEPTH and MAXVAL.
    const val REQUIRED_FIELDS = 4
  }
}
