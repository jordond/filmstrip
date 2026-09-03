package dev.jordond.filmstrip.internal

import android.content.Context
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The cache an in-memory source is written through before the platform can open it as a file.
 *
 * The claim is that the same buffer costs one file however many times it is read, which is what
 * stops a source probed and then exported being written twice.
 */
class AndroidScratchTest {
  private val cache = File(System.getProperty("java.io.tmpdir"), "android-scratch-test-${System.nanoTime()}")

  private val context =
    mockk<Context> {
      every { cacheDir } returns cache
    }

  @AfterTest
  fun removeCache() {
    cache.deleteRecursively()
  }

  @Test
  fun `writes the bytes under the extension it was given`() {
    val file = AndroidScratch.fileFor(context, PNG, "png")

    assertTrue(file.extension == "png", "expected a png extension, got ${file.name}")
    file.readBytes().toList() shouldBe PNG.toList()
  }

  @Test
  fun `a source names its file from its hint`() {
    val hinted = AndroidScratch.fileFor(context, MediaSource.Bytes(PNG, FormatHint.Mov))

    assertTrue(hinted.extension == "mov", "expected a mov extension, got ${hinted.name}")
  }

  @Test
  fun `an unhinted source claims no container`() {
    val unhinted = AndroidScratch.fileFor(context, MediaSource.Bytes(PNG))

    assertTrue(unhinted.extension == "tmp", "expected a tmp extension, got ${unhinted.name}")
  }

  @Test
  fun `a source probed and then lowered lands on one file`() {
    val source = MediaSource.Bytes(PNG, FormatHint.Mp4)

    val probed = AndroidScratch.fileFor(context, source)
    val lowered = AndroidScratch.fileFor(context, MediaSource.Bytes(PNG.copyOf(), FormatHint.Mp4))

    lowered shouldBe probed
  }

  @Test
  fun `the same bytes lowered twice are written once`() {
    val first = AndroidScratch.fileFor(context, PNG, "png")

    val sentinel = first.readBytes()
    sentinel[sentinel.lastIndex] = sentinel[sentinel.lastIndex].inc()
    first.writeBytes(sentinel)

    val second = AndroidScratch.fileFor(context, PNG.copyOf(), "png")

    second shouldBe first
    second.readBytes().toList() shouldBe sentinel.toList()
  }

  @Test
  fun `different bytes are written apart from each other`() {
    val first = AndroidScratch.fileFor(context, PNG, "png")
    val second = AndroidScratch.fileFor(context, JPEG, "jpeg")

    assertTrue(first != second, "two different buffers landed on ${first.name}")
    first.readBytes().toList() shouldBe PNG.toList()
    second.readBytes().toList() shouldBe JPEG.toList()
  }

  @Test
  fun `nothing partial is left beside a written file`() {
    val file = AndroidScratch.fileFor(context, PNG, "png")

    val partials =
      file.parentFile
        ?.listFiles()
        ?.filter { it.name.endsWith(".partial") }
        .orEmpty()
    partials shouldBe emptyList()
  }

  private companion object {
    val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02)
    val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x03, 0x04)
  }
}
