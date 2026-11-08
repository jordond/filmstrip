package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.fixtures.PNG_EXIF_AT
import dev.jordond.filmstrip.picture.fixtures.exifBlock
import dev.jordond.filmstrip.picture.fixtures.heifOriented
import dev.jordond.filmstrip.picture.fixtures.pngWithExif
import dev.jordond.filmstrip.picture.fixtures.webpWithExif
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

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

        withClue("$name $value") { pictureOrientationOf(written) shouldBe PictureOrientation.Normal }
        withClue("$name $value") { written.size shouldBe tagged.size }
        val changed = tagged.indices.filter { tagged[it] != written[it] }
        val most = if (name.startsWith("png")) 2 + PNG_CRC_BYTES else 2
        withClue("$name $value changed bytes at $changed") {
          (changed.isNotEmpty() && changed.size <= most).shouldBeTrue()
        }
      }
    }
  }

  @Test
  fun aPngWithTheTagWrittenKeepsAChunkCrcThatHolds() {
    val written = pictureWithoutOrientationTag(pngWithExif(exifBlock(6)))
    val view = ByteView(written, written.size)
    val length = view.u32(PNG_EXIF_AT).toInt()
    val end = PNG_EXIF_AT + 8 + length

    view.crc32(PNG_EXIF_AT + 4, end) shouldBe view.u32(end)
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
    for ((name, bytes) in untouched) withClue(name) { pictureWithoutOrientationTag(bytes) shouldBeSameInstanceAs bytes }
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
