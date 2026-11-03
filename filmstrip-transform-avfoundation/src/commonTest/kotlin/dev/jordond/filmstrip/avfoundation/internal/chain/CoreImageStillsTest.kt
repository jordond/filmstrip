package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.photoFixture
import dev.jordond.filmstrip.avfoundation.readBytes
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The stills a chain holds open for as long as it runs, and what it says when one cannot be opened.
 */
class CoreImageStillsTest {
  @Test
  fun `a path is opened once and handed back on every later frame`() {
    val stills = CoreImageStills()
    val path = photo()

    val first = stills.of(ImageSource.of(path))

    stills.of(ImageSource.of(path)) shouldBeSameInstanceAs first
    assertNear(first.rect(), listOf(0.0, 0.0, PHOTO.width.toDouble(), PHOTO.height.toDouble()))
  }

  @Test
  fun `a file uri opens the same photo a path does`() {
    val stills = CoreImageStills()
    val uri = NSURL.fileURLWithPath(photo()).absoluteString.shouldNotBeNull()

    val image = stills.of(ImageSource.ofUri(uri))

    stills.of(ImageSource.ofUri(uri)) shouldBeSameInstanceAs image
    assertNear(image.rect(), listOf(0.0, 0.0, PHOTO.width.toDouble(), PHOTO.height.toDouble()))
  }

  @Test
  fun `bytes are looked up by the buffer rather than by what it holds`() {
    val stills = CoreImageStills()
    val bytes = readBytes(photo())

    val first = stills.of(ImageSource.ofBytes(bytes))

    stills.of(ImageSource.ofBytes(bytes)) shouldBeSameInstanceAs first
    stills.of(ImageSource.ofBytes(bytes.copyOf())) shouldNotBeSameInstanceAs first
    assertNear(first.rect(), listOf(0.0, 0.0, PHOTO.width.toDouble(), PHOTO.height.toDouble()))
  }

  @Test
  fun `a missing file fails the lowering and names the path`() {
    val failure = assertFailsWith<AvLoweringFailure> { CoreImageStills().of(ImageSource.of(MISSING)) }

    failure.reason shouldContain MISSING
  }

  @Test
  fun `empty bytes fail the lowering rather than drawing nothing`() {
    val failure = assertFailsWith<AvLoweringFailure> { CoreImageStills().of(ImageSource.ofBytes(ByteArray(0))) }

    failure.reason shouldContain "bytes[0]"
  }

  @Test
  fun `bytes no decoder reads fail the lowering`() {
    assertFailsWith<AvLoweringFailure> { CoreImageStills().of(ImageSource.ofBytes(ByteArray(64) { it.toByte() })) }
  }

  private fun photo(): String = photoFixture("core-image-stills", PHOTO, Triple(200, 120, 40))

  private companion object {
    val PHOTO = Size(48, 32)
    const val MISSING = "/filmstrip/does-not-exist.png"
  }
}
