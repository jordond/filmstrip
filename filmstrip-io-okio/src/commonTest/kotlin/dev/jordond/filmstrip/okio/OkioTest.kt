package dev.jordond.filmstrip.okio

import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * A path keeps the string okio renders it as, and a source or a byte string is turned into bytes.
 * Nothing else happens in this module, so nothing else is asserted.
 */
class OkioTest {
  private val path = "/tmp/filmstrip/clip.mp4".toPath()

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
    val buffer = Buffer().apply { write(BYTES) }

    assertIs<ImageSource.Bytes>(buffer.readImageSource()).bytes shouldBe BYTES
    buffer.exhausted() shouldBe true
  }

  @Test
  fun aByteStringReadsAsItsOwnBytes() {
    val bytes = BYTES.toByteString()

    val source = assertIs<MediaSource.Bytes>(bytes.toMediaSource(FormatHint.Mov))

    source.bytes shouldBe BYTES
    source.hint shouldBe FormatHint.Mov
    assertIs<ImageSource.Bytes>(bytes.toImageSource()).bytes shouldBe BYTES
  }

  private companion object {
    val BYTES = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
  }
}
