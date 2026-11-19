package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.photoFixture
import dev.jordond.filmstrip.avfoundation.readBytes
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * The stills a chain holds open for as long as it runs, and what it says when one cannot be opened.
 */
class CoreImageStillsTest {
  @Test
  fun `a path is opened once and handed back on every later frame`() {
    val stills = CoreImageStills()
    val path = photo()

    val first = stills.of(held(ImageSource.of(path)), PHOTO, null)

    stills.of(held(ImageSource.of(path)), PHOTO, null) shouldBeSameInstanceAs first
    assertNear(first.rect(), listOf(0.0, 0.0, PHOTO.width.toDouble(), PHOTO.height.toDouble()))
  }

  @Test
  fun `a file uri opens the same photo a path does`() {
    val stills = CoreImageStills()
    val uri = NSURL.fileURLWithPath(photo()).absoluteString.shouldNotBeNull()

    val image = stills.of(held(ImageSource.ofUri(uri)), PHOTO, null)

    stills.of(held(ImageSource.ofUri(uri)), PHOTO, null) shouldBeSameInstanceAs image
    assertNear(image.rect(), listOf(0.0, 0.0, PHOTO.width.toDouble(), PHOTO.height.toDouble()))
  }

  @Test
  fun `a solid opens once per grade it is drawn on`() {
    val stills = CoreImageStills()
    val solid = MediaSource.Solid(0xFF336699.toInt(), 1.seconds)

    val sdr = stills.of(solid, PHOTO, null)
    val pq = stills.of(solid, PHOTO, HdrTransfer.Pq)

    pq shouldNotBeSameInstanceAs sdr
    stills.of(solid, PHOTO, HdrTransfer.Hlg) shouldNotBeSameInstanceAs pq
    stills.of(solid, PHOTO, HdrTransfer.Pq) shouldBeSameInstanceAs pq
  }

  @Test
  fun `bytes are looked up by the buffer rather than by what it holds`() {
    val stills = CoreImageStills()
    val bytes = readBytes(photo())

    val first = stills.of(held(ImageSource.ofBytes(bytes)), PHOTO, null)

    stills.of(held(ImageSource.ofBytes(bytes)), PHOTO, null) shouldBeSameInstanceAs first
    stills.of(held(ImageSource.ofBytes(bytes.copyOf())), PHOTO, null) shouldNotBeSameInstanceAs first
    assertNear(first.rect(), listOf(0.0, 0.0, PHOTO.width.toDouble(), PHOTO.height.toDouble()))
  }

  @Test
  fun `a solid is its colour over the size it was laid at`() {
    val stills = CoreImageStills()
    val solid = MediaSource.Solid(0xFF336699.toInt(), 1.seconds)

    val image = stills.of(solid, PHOTO, null)

    stills.of(solid, PHOTO, null) shouldBeSameInstanceAs image
    stills.of(solid, Size(8, 8), null) shouldNotBeSameInstanceAs image
    assertNear(image.rect(), listOf(0.0, 0.0, PHOTO.width.toDouble(), PHOTO.height.toDouble()))
  }

  @Test
  fun `a missing file fails the lowering and names the path`() {
    val failure =
      assertFailsWith<AvLoweringFailure> { CoreImageStills().of(held(ImageSource.of(MISSING)), PHOTO, null) }

    failure.reason shouldContain MISSING
  }

  @Test
  fun `empty bytes fail the lowering rather than drawing nothing`() {
    val empty = held(ImageSource.ofBytes(ByteArray(0)))
    val failure = assertFailsWith<AvLoweringFailure> { CoreImageStills().of(empty, PHOTO, null) }

    failure.reason shouldContain "bytes[0]"
  }

  @Test
  fun `bytes no decoder reads fail the lowering`() {
    val noise = held(ImageSource.ofBytes(ByteArray(64) { it.toByte() }))
    assertFailsWith<AvLoweringFailure> { CoreImageStills().of(noise, PHOTO, null) }
  }

  private fun photo(): String = photoFixture("core-image-stills", PHOTO, Triple(200, 120, 40))

  private companion object {
    val PHOTO = Size(48, 32)
    const val MISSING = "/filmstrip/does-not-exist.png"
  }
}

private fun held(image: ImageSource): MediaSource = MediaSource.Image(image, 1.seconds)
