package dev.jordond.filmstrip

import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [MediaSource.Bytes] is used as a map key on the probe path, so its hash is memoized. These pin
 * the contract that memoizing has to keep.
 */
class MediaSourceTest {
  @Test
  fun bytesHoldingTheSameContentAreStillEqual() {
    val a = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)
    val b = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)

    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }

  @Test
  fun bytesHoldingDifferentContentAreNotEqual() {
    val a = MediaSource.Bytes(byteArrayOf(1, 2, 3))
    val b = MediaSource.Bytes(byteArrayOf(1, 2, 4))

    assertNotEquals(a, b)
  }

  @Test
  fun theHintParticipatesInEquality() {
    val mp4 = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)
    val mov = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mov)

    assertNotEquals(mp4, mov)
  }

  @Test
  fun theHashIsComputedOnceAndThenReused() {
    val bytes = byteArrayOf(9, 8, 7)
    val source = MediaSource.Bytes(bytes, FormatHint.Mov)
    val first = source.hashCode()

    // Mutating a source's array is unsupported, which is what makes the memo sound. Doing it here
    // is how the memo becomes observable: a recomputing hash would follow the edit.
    bytes[0] = 100

    assertEquals(first, source.hashCode())
    assertNotEquals(first, MediaSource.Bytes(bytes, FormatHint.Mov).hashCode())
  }

  // The memo fills in the first time something asks for it, so a source that has been hashed and
  // one straight from a constructor have to still match, in either direction.
  @Test
  fun bytesAreEqualWhetherOrNotTheHashHasBeenAskedFor() {
    val hashed = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)
    hashed.hashCode()
    val fresh = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)

    assertEquals(hashed, fresh)
    assertEquals(fresh, hashed)
  }

  @Test
  fun bytesSurviveBeingUsedAsAMapKey() {
    val key = MediaSource.Bytes(byteArrayOf(4, 5, 6), FormatHint.M4a)
    val cache = mutableMapOf<MediaSource, String>(key to "probed")

    assertEquals("probed", cache[key])
    assertEquals("probed", cache[MediaSource.Bytes(byteArrayOf(4, 5, 6), FormatHint.M4a)])
    assertEquals(1, listOf(key, MediaSource.Bytes(byteArrayOf(4, 5, 6), FormatHint.M4a)).distinct().size)
  }

  @Test
  fun theMemoizedHashStaysOutOfTheSerialForm() {
    val source: MediaSource = MediaSource.Bytes(byteArrayOf(1, 2, 3), FormatHint.Mp4)
    source.hashCode()

    val json = Json.encodeToString(MediaSource.serializer(), source)

    assertFalse(json.contains("emoizedHash"), "the hash memo is not part of the wire format")
    assertTrue(json.contains("\"bytes\""), "the payload is still persisted")
    assertEquals(source, Json.decodeFromString(MediaSource.serializer(), json))
  }
}
