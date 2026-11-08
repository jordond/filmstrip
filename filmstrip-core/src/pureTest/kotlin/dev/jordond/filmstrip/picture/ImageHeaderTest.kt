package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.probe.ROTATED_IMAGE_PROBE_ORIENTATION
import dev.jordond.filmstrip.media.probe.imageProbeBytes
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import dev.jordond.filmstrip.picture.fixtures.exifBlock
import dev.jordond.filmstrip.picture.fixtures.heifOriented
import dev.jordond.filmstrip.picture.fixtures.jxlCodestream
import dev.jordond.filmstrip.picture.fixtures.pngWithExif
import dev.jordond.filmstrip.picture.fixtures.webpWithExif
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The header read the JVM and a browser both answer out of, so the two of them cannot read the same
 * bytes differently and neither can drift from what Android and Apple read through their own.
 */
class ImageHeaderTest {
  @Test
  fun theTagTheFixtureWritesIsTheTagTheReadReportsBack() {
    val header = rotatedImageProbeBytes()

    exifOrientationOf(header) shouldBe ROTATED_IMAGE_PROBE_ORIENTATION
  }

  // The middle of the range matters as much as its ends: the four mirrored orientations are the ones
  // a read that treated the tag as a count of quarter turns would still get right at 1 and wrong at
  // 5, so every value the tag defines is read back rather than only the two that bracket them.
  @Test
  fun everyOrientationTheTagDefinesIsReadBackAsItself() {
    val orientations = (EXIF_ORIENTATION_NORMAL..LAST_ORIENTATION).toList()

    orientations.map { exifOrientationOf(rotatedImageProbeBytes(it)) } shouldBe orientations
  }

  // A camera writes either byte order and says which in the first two bytes of the block.
  @Test
  fun aLittleEndianBlockReadsTheSameAsABigEndianOne() {
    exifOrientationOf(rotatedImageProbeBytes(SIDEWAYS, bigEndian = true)) shouldBe SIDEWAYS
    exifOrientationOf(rotatedImageProbeBytes(SIDEWAYS, bigEndian = false)) shouldBe SIDEWAYS
  }

  @Test
  fun aStillWithNoExifBlockAtAllReadsAsNormal() {
    exifOrientationOf(imageProbeBytes()) shouldBe EXIF_ORIENTATION_NORMAL
    exifOrientationOf(PNG_HEADER) shouldBe EXIF_ORIENTATION_NORMAL
  }

  // Every one of these is a file the caller handed over, so a read of one answers rather than
  // throwing and leaving a probe to decide what an exception meant.
  @Test
  fun aHeaderThatIsNotOneReadsAsNormalRatherThanThrowing() {
    exifOrientationOf(ByteArray(0)) shouldBe EXIF_ORIENTATION_NORMAL
    exifOrientationOf(byteArrayOf(1, 2, 3, 4)) shouldBe EXIF_ORIENTATION_NORMAL
    exifOrientationOf(byteArrayOf(0xFF.toByte())) shouldBe EXIF_ORIENTATION_NORMAL
  }

  @Test
  fun aBlockThatStopsBeforeTheTagsValueReadsAsNormal() {
    val whole = rotatedImageProbeBytes()

    for (length in 0 until VALUE_END) {
      withClue("a header of $length bytes") { exifOrientationOf(whole.copyOf(length)) shouldBe EXIF_ORIENTATION_NORMAL }
    }
  }

  // A file cut short inside its Exif segment still holds the tag if the cut falls past it.
  @Test
  fun aBlockCutOffJustPastTheTagsValueStillReadsIt() {
    val head = rotatedImageProbeBytes().copyOf(VALUE_END)

    exifOrientationOf(head) shouldBe ROTATED_IMAGE_PROBE_ORIENTATION
  }

  @Test
  fun aBlockClaimingAnImpossibleLengthReadsAsNormal() {
    val header = rotatedImageProbeBytes()
    header[SEGMENT_LENGTH] = 0
    header[SEGMENT_LENGTH + 1] = 0

    exifOrientationOf(header) shouldBe EXIF_ORIENTATION_NORMAL
  }

  // A still that is not a JPEG says which way up it is too, and the JVM and a browser read it the way Apple and
  // Android's own readers already do.
  @Test
  fun aStillInAnyContainerTheOrientationReadKnowsReadsItsTag() {
    val tagged =
      listOf(
        pngWithExif(exifBlock(SIDEWAYS)),
        webpWithExif(exifBlock(SIDEWAYS)),
        exifBlock(SIDEWAYS),
        heifOriented(turns = 3, axis = null),
        jxlCodestream(SIDEWAYS),
      )
    for (bytes in tagged) exifOrientationOf(bytes) shouldBe SIDEWAYS
  }

  @Test
  fun everyFormatAStillArrivesInIsNamedFromItsOwnMagicBytes() {
    imageMediaTypeOf(rotatedImageProbeBytes()) shouldBe "image/jpeg"
    imageMediaTypeOf(imageProbeBytes()) shouldBe "image/bmp"
    imageMediaTypeOf(PNG_HEADER) shouldBe "image/png"
    imageMediaTypeOf(GIF_HEADER) shouldBe "image/gif"
    imageMediaTypeOf(WEBP_HEADER) shouldBe "image/webp"
  }

  // The trailing word is what a probe reports as the format, and it has to be the word the other
  // three targets' own readers hand back for the same file.
  @Test
  fun theTrailingWordIsTheOneEveryTargetSpellsTheFormatWith() {
    imageMediaTypeOf(rotatedImageProbeBytes()).substringAfterLast('/') shouldBe "jpeg"
    imageMediaTypeOf(imageProbeBytes()).substringAfterLast('/') shouldBe "bmp"
  }

  @Test
  fun aFormatNothingHereRecognisesIsNamedEmptyRatherThanGuessedAt() {
    imageMediaTypeOf(ByteArray(0)) shouldBe ""
    imageMediaTypeOf(byteArrayOf(1, 2, 3, 4)) shouldBe ""
    // A container that is RIFF without being a WebP, which is what reading only the first four
    // bytes would call one.
    imageMediaTypeOf("RIFF____WAVEfmt ".encodeToByteArray()) shouldBe ""
  }

  private companion object {
    const val SIDEWAYS = 6
    const val LAST_ORIENTATION = 8

    /**
     * Where the APP1 length sits, two bytes past the SOI and the marker.
     */
    const val SEGMENT_LENGTH = 4

    /**
     * The end of the tag's two byte value, which is the least of the fixture a read has to see.
     */
    const val VALUE_END = 32

    val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)

    val GIF_HEADER = "GIF89a".encodeToByteArray()

    val WEBP_HEADER = "RIFF____WEBPVP8 ".encodeToByteArray()
  }
}
