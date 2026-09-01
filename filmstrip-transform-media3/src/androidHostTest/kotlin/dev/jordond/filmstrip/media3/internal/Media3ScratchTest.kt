package dev.jordond.filmstrip.media3.internal

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The cache an in-memory source is written through before media3 can open it.
 *
 * The claim is that the same buffer costs one file however many times it is lowered, which is what
 * stops a composition holding a title card leaving a copy of it behind on every export.
 */
class Media3ScratchTest {
  private val cache = File(System.getProperty("java.io.tmpdir"), "media3-scratch-test-${System.nanoTime()}")

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
    val file = Media3Scratch.fileFor(context, PNG, "png")

    assertTrue(file.extension == "png", "expected a png extension, got ${file.name}")
    file.readBytes().toList() shouldBe PNG.toList()
  }

  @Test
  fun `the same bytes lowered twice are written once`() {
    val first = Media3Scratch.fileFor(context, PNG, "png")

    val sentinel = first.readBytes()
    sentinel[sentinel.lastIndex] = sentinel[sentinel.lastIndex].inc()
    first.writeBytes(sentinel)

    val second = Media3Scratch.fileFor(context, PNG.copyOf(), "png")

    second shouldBe first
    second.readBytes().toList() shouldBe sentinel.toList()
  }

  @Test
  fun `different bytes are written apart from each other`() {
    val first = Media3Scratch.fileFor(context, PNG, "png")
    val second = Media3Scratch.fileFor(context, JPEG, "jpeg")

    assertTrue(first != second, "two different buffers landed on ${first.name}")
    first.readBytes().toList() shouldBe PNG.toList()
    second.readBytes().toList() shouldBe JPEG.toList()
  }

  @Test
  fun `nothing partial is left beside a written file`() {
    val file = Media3Scratch.fileFor(context, PNG, "png")

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
