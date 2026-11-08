package dev.jordond.filmstrip

import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * [MediaSource.Bytes] is used as a map key on the probe path, so its hash is memoized. These pin
 * the contract that memoizing has to keep.
 */
class MediaSourceTest {
  @Test
  fun bytesHoldingTheSameContentAreStillEqual() {
    val a = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)
    val b = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)

    b shouldBe a
    b.hashCode() shouldBe a.hashCode()
  }

  @Test
  fun bytesHoldingDifferentContentAreNotEqual() {
    val a = MediaSource.Bytes(byteArrayOf(1, 2, 3))
    val b = MediaSource.Bytes(byteArrayOf(1, 2, 4))

    b shouldNotBe a
  }

  @Test
  fun theHintParticipatesInEquality() {
    val mp4 = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)
    val mov = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mov)

    mov shouldNotBe mp4
  }

  @Test
  fun theHashIsComputedOnceAndThenReused() {
    val bytes = byteArrayOf(9, 8, 7)
    val source = MediaSource.Bytes(bytes, FormatHint.Mov)
    val first = source.hashCode()

    // Mutating a source's array is unsupported, which is what makes the memo sound. Doing it here
    // is how the memo becomes observable: a recomputing hash would follow the edit.
    bytes[0] = 100

    source.hashCode() shouldBe first
    MediaSource.Bytes(bytes, FormatHint.Mov).hashCode() shouldNotBe first
  }

  // The memo fills in the first time something asks for it, so a source that has been hashed and
  // one straight from a constructor have to still match, in either direction.
  @Test
  fun bytesAreEqualWhetherOrNotTheHashHasBeenAskedFor() {
    val hashed = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)
    hashed.hashCode()
    val fresh = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)

    fresh shouldBe hashed
    hashed shouldBe fresh
  }

  @Test
  fun bytesSurviveBeingUsedAsAMapKey() {
    val key = MediaSource.Bytes(byteArrayOf(4, 5, 6), FormatHint.M4a)
    val cache = mutableMapOf<MediaSource, String>(key to "probed")

    cache[key] shouldBe "probed"
    cache[MediaSource.Bytes(byteArrayOf(4, 5, 6), FormatHint.M4a)] shouldBe "probed"
    listOf(key, MediaSource.Bytes(byteArrayOf(4, 5, 6), FormatHint.M4a)).distinct().size shouldBe 1
  }
}
