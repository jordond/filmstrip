package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.media.probe.ROTATED_IMAGE_PROBE_ORIENTATION
import dev.jordond.filmstrip.media.probe.imageProbeBytes
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The header read the JVM and a browser both answer out of, so the two of them cannot read the same
 * bytes differently and neither can drift from what Android and Apple read through their own.
 */
class ImageHeaderTest {
  @Test
  fun theTagTheFixtureWritesIsTheTagTheReadReportsBack() {
    val header = rotatedImageProbeBytes()

    assertEquals(ROTATED_IMAGE_PROBE_ORIENTATION, exifOrientationOf(header))
  }

  // The middle of the range matters as much as its ends: the four mirrored orientations are the ones
  // a read that treated the tag as a count of quarter turns would still get right at 1 and wrong at
  // 5, so every value the tag defines is read back rather than only the two that bracket them.
  @Test
  fun everyOrientationTheTagDefinesIsReadBackAsItself() {
    val orientations = (EXIF_ORIENTATION_NORMAL..LAST_ORIENTATION).toList()

    assertEquals(orientations, orientations.map { exifOrientationOf(rotatedImageProbeBytes(it)) })
  }

  // A camera writes either byte order and says which in the first two bytes of the block.
  @Test
  fun aLittleEndianBlockReadsTheSameAsABigEndianOne() {
    assertEquals(SIDEWAYS, exifOrientationOf(rotatedImageProbeBytes(SIDEWAYS, bigEndian = true)))
    assertEquals(SIDEWAYS, exifOrientationOf(rotatedImageProbeBytes(SIDEWAYS, bigEndian = false)))
  }

  @Test
  fun aStillWithNoExifBlockAtAllReadsAsNormal() {
    assertEquals(EXIF_ORIENTATION_NORMAL, exifOrientationOf(imageProbeBytes()))
    assertEquals(EXIF_ORIENTATION_NORMAL, exifOrientationOf(PNG_HEADER))
  }

  // Every one of these is a file the caller handed over, so a read of one answers rather than
  // throwing and leaving a probe to decide what an exception meant.
  @Test
  fun aHeaderThatIsNotOneReadsAsNormalRatherThanThrowing() {
    assertEquals(EXIF_ORIENTATION_NORMAL, exifOrientationOf(ByteArray(0)))
    assertEquals(EXIF_ORIENTATION_NORMAL, exifOrientationOf(byteArrayOf(1, 2, 3, 4)))
    assertEquals(EXIF_ORIENTATION_NORMAL, exifOrientationOf(byteArrayOf(0xFF.toByte())))
  }

  @Test
  fun aBlockThatStopsBeforeTheTagsValueReadsAsNormal() {
    val whole = rotatedImageProbeBytes()

    for (length in 0 until VALUE_END) {
      assertEquals(
        EXIF_ORIENTATION_NORMAL,
        exifOrientationOf(whole.copyOf(length)),
        "a header of $length bytes",
      )
    }
  }

  // The JVM and a browser both hand over the head of a file rather than all of it, so a read that
  // needed the rest of the segment would answer normal for every photo either of them measured.
  @Test
  fun aBlockCutOffJustPastTheTagsValueStillReadsIt() {
    val head = rotatedImageProbeBytes().copyOf(VALUE_END)

    assertEquals(ROTATED_IMAGE_PROBE_ORIENTATION, exifOrientationOf(head))
  }

  @Test
  fun aBlockClaimingAnImpossibleLengthReadsAsNormal() {
    val header = rotatedImageProbeBytes()
    header[SEGMENT_LENGTH] = 0
    header[SEGMENT_LENGTH + 1] = 0

    assertEquals(EXIF_ORIENTATION_NORMAL, exifOrientationOf(header))
  }

  @Test
  fun everyFormatAStillArrivesInIsNamedFromItsOwnMagicBytes() {
    assertEquals("image/jpeg", imageMediaTypeOf(rotatedImageProbeBytes()))
    assertEquals("image/bmp", imageMediaTypeOf(imageProbeBytes()))
    assertEquals("image/png", imageMediaTypeOf(PNG_HEADER))
    assertEquals("image/gif", imageMediaTypeOf(GIF_HEADER))
    assertEquals("image/webp", imageMediaTypeOf(WEBP_HEADER))
  }

  // The trailing word is what a probe reports as the format, and it has to be the word the other
  // three targets' own readers hand back for the same file.
  @Test
  fun theTrailingWordIsTheOneEveryTargetSpellsTheFormatWith() {
    assertEquals("jpeg", imageMediaTypeOf(rotatedImageProbeBytes()).substringAfterLast('/'))
    assertEquals("bmp", imageMediaTypeOf(imageProbeBytes()).substringAfterLast('/'))
  }

  @Test
  fun aFormatNothingHereRecognisesIsNamedEmptyRatherThanGuessedAt() {
    assertEquals("", imageMediaTypeOf(ByteArray(0)))
    assertEquals("", imageMediaTypeOf(byteArrayOf(1, 2, 3, 4)))
    // A container that is RIFF without being a WebP, which is what reading only the first four
    // bytes would call one.
    assertEquals("", imageMediaTypeOf("RIFF____WAVEfmt ".encodeToByteArray()))
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
