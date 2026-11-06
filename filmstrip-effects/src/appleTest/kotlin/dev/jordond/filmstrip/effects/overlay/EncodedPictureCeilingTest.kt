package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.writeToFile
import kotlin.test.Test

/**
 * A picture's encoded bytes are held under the one picture ceiling, and refused by name past it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class EncodedPictureCeilingTest {
  // One byte past the ceiling and sparse, so a read that did not stop at the file's length would cost a whole gigabyte.
  @Test
  fun aFilePastThePictureCeilingIsRefusedByNameWithoutBeingRead() {
    val path = NSTemporaryDirectory() + "filmstrip-encoded-past-ceiling.bin"
    write(path, ByteArray(1))
    try {
      val handle = NSFileHandle.fileHandleForWritingAtPath(path)!!
      handle.truncateFileAtOffset((PICTURE_PEAK_CEILING + 1).toULong())
      handle.closeFile()
      val image = ImageSource.Path(path)

      val read = image.encodedRead()
      read.bytes.shouldBeNull()
      read.refusal shouldBe pictureTooLargeToRead(image)
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
  }

  @Test
  fun theEncodedBytesAreHeldUpToTheCeilingAndRefusedOnePastIt() {
    val path = NSTemporaryDirectory() + "filmstrip-encoded-at-ceiling.bin"
    val bytes = ByteArray(32) { it.toByte() }
    write(path, bytes)
    try {
      val image = ImageSource.Path(path)

      image.encodedRead(bytes.size.toLong()).bytes shouldBe bytes
      image.encodedRead(bytes.size * 2L).bytes shouldBe bytes
      val past = image.encodedRead(bytes.size - 1L)
      past.bytes.shouldBeNull()
      past.refusal shouldBe pictureTooLargeToRead(image, bytes.size - 1L)
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
  }

  private fun write(
    path: String,
    bytes: ByteArray,
  ) {
    bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }.writeToFile(path, false)
  }
}
