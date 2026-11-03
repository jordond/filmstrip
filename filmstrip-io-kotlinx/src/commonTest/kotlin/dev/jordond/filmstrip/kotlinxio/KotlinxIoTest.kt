package dev.jordond.filmstrip.kotlinxio

import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.writeString
import kotlin.test.Test

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
    path.toMediaSource().shouldBeInstanceOf<MediaSource.Path>().path shouldBe path.toString()
    path.toImageSource().shouldBeInstanceOf<ImageSource.Path>().path shouldBe path.toString()
    path.toMediaSink().shouldBeInstanceOf<MediaSink.Path>().path shouldBe path.toString()
  }

  @Test
  fun aSourceIsDrainedWithTheHintItWasGiven() {
    val buffer = Buffer().apply { write(BYTES) }

    val source = buffer.readMediaSource(FormatHint.Mp4).shouldBeInstanceOf<MediaSource.Bytes>()

    source.bytes shouldBe BYTES
    source.hint shouldBe FormatHint.Mp4
    buffer.exhausted() shouldBe true
  }

  @Test
  fun aSourceWithNoHintLeavesTheBackendToSniff() {
    Buffer()
      .apply { write(BYTES) }
      .readMediaSource()
      .shouldBeInstanceOf<MediaSource.Bytes>()
      .hint shouldBe null
  }

  @Test
  fun aSourceIsDrainedAsAnImage() {
    val buffer = Buffer().apply { writeString(TEXT) }

    buffer.readImageSource().shouldBeInstanceOf<ImageSource.Bytes>().bytes shouldBe TEXT.encodeToByteArray()
    buffer.exhausted() shouldBe true
  }

  private companion object {
    val BYTES = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    const val TEXT = "not really a png"
  }
}
