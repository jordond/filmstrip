package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.picture.bytes.ascii
import dev.jordond.filmstrip.picture.png.PNG_SIGNATURE
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * How [pictureContainerOf] tells containers apart by their opening bytes, and how the readers built on it agree.
 */
class PictureContainerOfTest {
  @Test
  fun `each container is told by its own opening bytes`() {
    val cases =
      mapOf(
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) to PictureContainer.Jpeg,
        PNG_SIGNATURE + ByteArray(4) to PictureContainer.Png,
        ascii("RIFF") + ByteArray(4) + ascii("WEBP") to PictureContainer.Webp,
        ascii("II*\u0000") + ByteArray(4) to PictureContainer.Tiff,
        ascii("MM\u0000+") + ByteArray(4) to PictureContainer.Tiff,
        byteArrayOf(0xFF.toByte(), 0x0A, 0) to PictureContainer.JxlCodestream,
        byteArrayOf(0, 0, 0, 0x0C) + ascii("JXL ") + byteArrayOf(0x0D, 0x0A, 0x87.toByte(), 0x0A) to
          PictureContainer.JxlBoxed,
        byteArrayOf(0, 0, 0, 0x18) + ascii("ftypavif") to PictureContainer.Heif,
      )

    for ((bytes, container) in cases) withClue(container.name) { pictureContainerOf(bytes) shouldBe container }
  }

  @Test
  fun `a signature cut short or of another format opens as no container`() {
    pictureContainerOf(ByteArray(0)).shouldBeNull()
    pictureContainerOf(PNG_SIGNATURE.copyOf(7)).shouldBeNull()
    pictureContainerOf(ascii("RIFF") + ByteArray(4) + ascii("WAVE")).shouldBeNull()
    pictureContainerOf(ascii("GIF89a")).shouldBeNull()
  }

  @Test
  fun `the media type a header names follows its container`() {
    imageMediaTypeOf(ascii("RIFF") + ByteArray(4) + ascii("WEBP")) shouldBe "image/webp"
    imageMediaTypeOf(byteArrayOf(0, 0, 0, 0x18) + ascii("ftypavif")) shouldBe ""
    imageMediaTypeOf(byteArrayOf(0xFF.toByte(), 0x0A, 0)) shouldBe ""
    imageMediaTypeOf(ascii("GIF89a")) shouldBe "image/gif"
  }
}
