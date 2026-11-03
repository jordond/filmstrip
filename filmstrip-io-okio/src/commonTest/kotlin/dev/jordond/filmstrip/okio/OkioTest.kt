package dev.jordond.filmstrip.okio

import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import kotlin.test.Test

/**
 * A path keeps the string okio renders it as, and a source or a byte string is turned into bytes.
 * Nothing else happens in this module, so nothing else is asserted.
 */
class OkioTest {
  private val path = "/tmp/filmstrip/clip.mp4".toPath()

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
    val buffer = Buffer().apply { write(BYTES) }

    buffer.readImageSource().shouldBeInstanceOf<ImageSource.Bytes>().bytes shouldBe BYTES
    buffer.exhausted() shouldBe true
  }

  @Test
  fun aByteStringReadsAsItsOwnBytes() {
    val bytes = BYTES.toByteString()

    val source = bytes.toMediaSource(FormatHint.Mov).shouldBeInstanceOf<MediaSource.Bytes>()

    source.bytes shouldBe BYTES
    source.hint shouldBe FormatHint.Mov
    bytes.toImageSource().shouldBeInstanceOf<ImageSource.Bytes>().bytes shouldBe BYTES
  }

  private companion object {
    val BYTES = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
  }
}
