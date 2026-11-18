package dev.jordond.filmstrip.picture.animated

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AnimatedAvifRefusalTest {
  @Test
  fun `an avis major brand is refused by name`() {
    animatedPictureOf(ftyp("avis", "avis", "msf1", "iso8")).refusal() shouldBe ANIMATED_AVIF_REFUSED
  }

  @Test
  fun `an avis compatible brand is refused by name`() {
    animatedPictureOf(ftyp("avif", "avif", "mif1", "avis")).refusal() shouldBe ANIMATED_AVIF_REFUSED
  }

  @Test
  fun `a header with nothing behind it is refused before anything is decoded`() {
    animatedPictureOf(ftyp("avis")).refusal() shouldBe ANIMATED_AVIF_REFUSED
  }

  @Test
  fun `an avif still is not refused`() {
    animatedPictureOf(ftyp("avif", "avif", "mif1", "miaf")) shouldBe AnimatedRead.Still
  }

  @Test
  fun `a heic is not refused`() {
    animatedPictureOf(ftyp("heic", "heic", "mif1")) shouldBe AnimatedRead.Still
  }

  @Test
  fun `a brand the box does not hold is not read`() {
    val box = ftyp("avif", "avif", "mif1")

    animatedPictureOf(box + "avis".encodeToByteArray()) shouldBe AnimatedRead.Still
  }

  // An `ftyp` box with [major] as its major brand, then the minor version and [compatible] as its other brands.
  private fun ftyp(
    major: String,
    vararg compatible: String,
  ): ByteArray {
    val body =
      major.encodeToByteArray() + ByteArray(4) +
        compatible.fold(ByteArray(0)) { all, it -> all + it.encodeToByteArray() }
    val size = body.size + 8
    return byteArrayOf(0, 0, (size shr 8).toByte(), size.toByte()) + "ftyp".encodeToByteArray() + body
  }
}
