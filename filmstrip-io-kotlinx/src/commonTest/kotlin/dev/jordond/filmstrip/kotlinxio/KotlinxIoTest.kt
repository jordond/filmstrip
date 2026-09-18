package dev.jordond.filmstrip.kotlinxio

import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * A path keeps the string kotlinx-io renders it as, and a source is drained into bytes. Nothing
 * else happens in this module, so nothing else is asserted.
 */
class KotlinxIoTest {
  // Single argument on purpose: the vararg overload joins with SystemPathSeparator, which a
  // browser cannot read because it comes from Node.
  private val path = Path("/tmp/filmstrip/clip.mp4")

  @Test
  fun aPathReadsAsItsOwnString() {
    assertIs<MediaSource.Path>(path.toMediaSource()).path shouldBe path.toString()
    assertIs<ImageSource.Path>(path.toImageSource()).path shouldBe path.toString()
    assertIs<MediaSink.Path>(path.toMediaSink()).path shouldBe path.toString()
  }

  @Test
  fun aSourceIsDrainedWithTheHintItWasGiven() {
    val buffer = Buffer().apply { write(BYTES) }

    val source = assertIs<MediaSource.Bytes>(buffer.readMediaSource(FormatHint.Mp4))

    source.bytes shouldBe BYTES
    source.hint shouldBe FormatHint.Mp4
    buffer.exhausted() shouldBe true
  }

  @Test
  fun aSourceWithNoHintLeavesTheBackendToSniff() {
    assertIs<MediaSource.Bytes>(Buffer().apply { write(BYTES) }.readMediaSource()).hint shouldBe null
  }

  @Test
  fun aSourceIsDrainedAsAnImage() {
    val buffer = Buffer().apply { writeString(TEXT) }

    assertIs<ImageSource.Bytes>(buffer.readImageSource()).bytes shouldBe TEXT.encodeToByteArray()
    buffer.exhausted() shouldBe true
  }

  private companion object {
    val BYTES = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    const val TEXT = "not really a png"
  }
}
