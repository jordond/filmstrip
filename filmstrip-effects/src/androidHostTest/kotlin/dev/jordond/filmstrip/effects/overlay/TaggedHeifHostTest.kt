package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.internal.pictureOrientationOf
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.RestatedHeif
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.heifWithRestatedColour
import dev.jordond.filmstrip.media.heifWithoutOrientation
import dev.jordond.filmstrip.test.OrientedPictures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Hands HEICs with an `irot` on their primary item to Android's decoder, checking the decoder never gets one it would
 * turn, whether or not core restates the codestream.
 */
class TaggedHeifHostTest {
  private val tagged = OrientedPictures.taggedHeif(heicRecording(BT709, false), quarterTurns = 1, mirrorAxis = null)

  @Test
  fun aTaggedPictureWhoseDropIsRefusedIsRefusedByNameOnBothRoutes() {
    val uncoded = tagged.renamed("hvc1", "hvcX").renamed("colr", "colX")
    assertNotNull(pictureYcbcrCoding(tagged))
    assertNull(pictureYcbcrCoding(uncoded))
    for (picture in listOf(tagged, uncoded)) {
      val sequence = picture + byteArrayOf(0, 0, 0, BOX_HEADER) + "moov".encodeToByteArray()
      val refused = assertIs<UnturnedHeif.Refused>(heifWithoutOrientation(sequence))

      assertEquals(refused.message, sequence.forAndroidDecoder(LATEST_API).refusal)
    }
  }

  @Test
  fun aTaggedPictureCoreCannotRestateStillLosesItsIrot() {
    val unread = tagged.renamed("hvcC", "hvcX")
    val described = unread.withColourDescriptionsHidden(keepNclxPrimaries = true)
    val unrestated = heifWithRestatedColour(described) { _, _, _ -> RestatedColour(BT601, BT709) }
    assertEquals(PictureOrientation.Normal, pictureOrientationOf(assertIs<RestatedHeif.Unrestated>(unrestated).bytes))
    assertNotEquals(PictureOrientation.Normal, pictureOrientationOf(unread))

    val input = unread.forAndroidDecoder(LATEST_API)

    assertNull(input.refusal)
    assertEquals(PictureOrientation.Normal, pictureOrientationOf(input.bytes))
  }

  // A copy with the first run of type's bytes written as replacement.
  private fun ByteArray.renamed(
    type: String,
    replacement: String,
  ): ByteArray =
    copyOf().also {
      val at = String(this, Charsets.ISO_8859_1).indexOf(type)
      replacement.encodeToByteArray().copyInto(it, at)
    }

  private companion object {
    const val BT709 = 1
    const val BT601 = 6
    const val LATEST_API = 36
    const val BOX_HEADER: Byte = 8
  }
}
