package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.FileKitUserDirectory
import io.github.vinceglb.filekit.userDirectory
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

private val BYTES = byteArrayOf(1, 2, 3, 4)

/**
 * The JVM gallery is the user's own videos and pictures directories, so each test reads the saved
 * file back out of one and deletes it again.
 */
class GalleryJvmTest {
  @Test
  fun `a sink lands in the videos directory`() =
    runTest {
      val written = sourceFile("mp4")

      try {
        val result = FileKit.saveVideoToGallery(MediaSink.of(written.absolutePath), written.name)
        assertSaved(result, FileKitUserDirectory.Videos, written.name)
      } finally {
        written.delete()
      }
    }

  @Test
  fun `a sink lands in the pictures directory`() =
    runTest {
      val written = sourceFile("png")

      try {
        val result = FileKit.saveImageToGallery(MediaSink.of(written.absolutePath), written.name)
        assertSaved(result, FileKitUserDirectory.Pictures, written.name)
      } finally {
        written.delete()
      }
    }

  @Test
  fun `no filename keeps the written file's own name`() =
    runTest {
      val written = sourceFile("mp4")

      try {
        val result = FileKit.saveVideoToGallery(MediaSink.of(written.absolutePath))
        assertSaved(result, FileKitUserDirectory.Videos, written.name)
      } finally {
        written.delete()
      }
    }

  private fun sourceFile(extension: String): File =
    File.createTempFile("filmstrip-gallery-", ".$extension").apply { writeBytes(BYTES) }

  // FileKit's JVM lookup calls mkdirs(), so on a bare machine this creates the user's Movies and
  // Pictures directories rather than failing.
  private fun assertSaved(
    result: Result<Unit>,
    directory: FileKitUserDirectory,
    name: String,
  ) {
    val saved = FileKit.userDirectory(directory).file.resolve(name)

    try {
      result.exceptionOrNull() shouldBe null
      saved.readBytes().toList() shouldBe BYTES.toList()
    } finally {
      saved.delete()
    }
  }
}
