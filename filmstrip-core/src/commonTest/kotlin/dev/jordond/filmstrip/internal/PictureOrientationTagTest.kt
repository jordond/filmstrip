package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Writes the orientation tag core reads as 1 in every container that keeps it in an Exif block, and checks that nothing
 * else in the file moves.
 */
class PictureOrientationTagTest {
  @Test
  fun everyTaggedContainerReadsAsNormalWithOnlyTheTagAndItsCrcWritten() {
    for (value in 2..8) {
      for ((name, tagged) in containers(value)) {
        val written = pictureWithoutOrientationTag(tagged)

        assertEquals(PictureOrientation.Normal, pictureOrientationOf(written), "$name $value")
        assertEquals(tagged.size, written.size, "$name $value")
        val changed = tagged.indices.filter { tagged[it] != written[it] }
        val most = if (name.startsWith("png")) 2 + PNG_CRC_BYTES else 2
        assertTrue(changed.isNotEmpty() && changed.size <= most, "$name $value changed bytes at $changed")
      }
    }
  }

  @Test
  fun aPngWithTheTagWrittenKeepsAChunkCrcThatHolds() {
    val written = pictureWithoutOrientationTag(pngWithExif(exifBlock(6)))
    val view = ByteView(written, written.size)
    val length = view.u32(PNG_EXIF_AT).toInt()
    val end = PNG_EXIF_AT + 8 + length

    assertEquals(view.u32(end), view.crc32(PNG_EXIF_AT + 4, end))
  }

  @Test
  fun bytesWithNoTagToWriteComeBackAsTheyAre() {
    val untouched =
      listOf(
        "jpeg tagged 1" to rotatedImageProbeBytes(1),
        "png tagged 1" to pngWithExif(exifBlock(1)),
        "png with a long" to pngWithExif(exifBlock(6, type = TIFF_LONG)),
        "webp unflagged" to webpWithExif(exifBlock(6), flagged = false),
        "heif turned" to heifOriented(turns = 1, axis = null),
      )
    for ((name, bytes) in untouched) assertSame(bytes, pictureWithoutOrientationTag(bytes), name)
  }

  private fun containers(value: Int): List<Pair<String, ByteArray>> =
    listOf(
      "jpeg big endian" to rotatedImageProbeBytes(value, bigEndian = true),
      "jpeg little endian" to rotatedImageProbeBytes(value, bigEndian = false),
      "png" to pngWithExif(exifBlock(value)),
      "png after image data" to pngWithExif(exifBlock(value, little = true), afterImageData = true),
      "webp" to webpWithExif(exifBlock(value)),
      "tiff" to exifBlock(value),
      "bigtiff" to exifBlock(value, little = true, big = true),
    )

  private companion object {
    const val PNG_CRC_BYTES = 4
    const val TIFF_LONG = 4
  }
}
