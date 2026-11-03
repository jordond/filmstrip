package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.heif.RestatedColor
import dev.jordond.filmstrip.picture.heif.RestatedHeif
import dev.jordond.filmstrip.picture.heif.UnturnedHeif
import dev.jordond.filmstrip.picture.heif.heifWithRestatedColor
import dev.jordond.filmstrip.picture.heif.heifWithoutOrientation
import dev.jordond.filmstrip.picture.pictureOrientationOf
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.picture.withColorDescriptionsHidden
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Hands HEICs with an `irot` on their primary item to Android's decoder, checking the decoder never gets one it would
 * turn, whether or not core restates the codestream.
 */
class TaggedHeifHostTest {
  private val tagged = OrientedPictures.taggedHeif(heicRecording(BT709, false), quarterTurns = 1, mirrorAxis = null)

  @Test
  fun aTaggedPictureWhoseDropIsRefusedIsRefusedByNameOnBothRoutes() {
    val uncoded = tagged.renamed("hvc1", "hvcX").renamed("colr", "colX")
    pictureYcbcrCoding(tagged).shouldNotBeNull()
    pictureYcbcrCoding(uncoded).shouldBeNull()
    for (picture in listOf(tagged, uncoded)) {
      val sequence = picture + byteArrayOf(0, 0, 0, BOX_HEADER) + "moov".encodeToByteArray()
      val refused = heifWithoutOrientation(sequence).shouldBeInstanceOf<UnturnedHeif.Refused>()

      sequence.forAndroidDecoder(LATEST_API).refusal shouldBe refused.message
    }
  }

  @Test
  fun aTaggedPictureCoreCannotRestateStillLosesItsIrot() {
    val unread = tagged.renamed("hvcC", "hvcX")
    val described = unread.withColorDescriptionsHidden(keepNclxPrimaries = true)
    val unrestated = heifWithRestatedColor(described) { _, _, _ -> RestatedColor(BT601, BT709) }
    pictureOrientationOf(unrestated.shouldBeInstanceOf<RestatedHeif.Unrestated>().bytes) shouldBe
      PictureOrientation.Normal
    pictureOrientationOf(unread) shouldNotBe PictureOrientation.Normal

    val input = unread.forAndroidDecoder(LATEST_API)

    input.refusal.shouldBeNull()
    pictureOrientationOf(input.bytes) shouldBe PictureOrientation.Normal
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
