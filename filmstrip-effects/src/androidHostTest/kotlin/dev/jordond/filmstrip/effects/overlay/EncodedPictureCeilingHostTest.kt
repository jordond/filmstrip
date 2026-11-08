package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test

/**
 * A picture's encoded bytes are held under the one picture ceiling, and refused by name past it.
 */
class EncodedPictureCeilingHostTest {
  // One byte past the ceiling, sparse, so a read that did not stop at the file's length would cost a whole gigabyte.
  @Test
  fun aFilePastThePictureCeilingIsRefusedByNameWithoutBeingRead() {
    val file = File.createTempFile("huge", ".png")
    try {
      RandomAccessFile(file, "rw").use { it.setLength(PICTURE_PEAK_CEILING + 1) }
      val image = ImageSource.Path(file.path)

      val read = image.encoded(null).shouldBeInstanceOf<EncodedPicture.Unread>().read
      val refused = read.shouldBeInstanceOf<RefusedPicture>()
      refused.reason shouldBe pictureTooLargeToRead(image)
      refused.reason shouldContain file.path
    } finally {
      file.delete()
    }
  }

  @Test
  fun aStreamWithNoLengthIsCutOffOncePastTheCeiling() {
    val bytes = ByteArray(32) { it.toByte() }

    bytes.inputStream().readWithin(bytes.size.toLong()) shouldBe bytes
    bytes.inputStream().readWithin(bytes.size * 2L) shouldBe bytes
    bytes.inputStream().readWithin(bytes.size - 1L).shouldBeNull()
  }

  @Test
  fun theEncodedBytesAreHeldUpToTheCeilingAndRefusedOnePastIt() {
    val file = File.createTempFile("ceiling", ".bin")
    try {
      val bytes = ByteArray(32) { it.toByte() }
      file.writeBytes(bytes)
      val image = ImageSource.Path(file.path)

      image.encoded(null, bytes.size.toLong()).shouldBeInstanceOf<EncodedPicture.Read>().bytes shouldBe bytes
      image.encoded(null, bytes.size * 2L).shouldBeInstanceOf<EncodedPicture.Read>().bytes shouldBe bytes
      val past = image.encoded(null, bytes.size - 1L).shouldBeInstanceOf<EncodedPicture.Unread>().read
      past.shouldBeInstanceOf<RefusedPicture>().reason shouldBe pictureTooLargeToRead(image, bytes.size - 1L)
    } finally {
      file.delete()
    }
  }
}
