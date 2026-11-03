package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.picture.fixtures.FIRST_HALF
import dev.jordond.filmstrip.picture.fixtures.MAX_PROFILE
import dev.jordond.filmstrip.picture.fixtures.PROFILE
import dev.jordond.filmstrip.picture.fixtures.SECOND_HALF
import dev.jordond.filmstrip.picture.fixtures.app2
import dev.jordond.filmstrip.picture.fixtures.asciiBytes
import dev.jordond.filmstrip.picture.fixtures.avif
import dev.jordond.filmstrip.picture.fixtures.bigTiff
import dev.jordond.filmstrip.picture.fixtures.embeddedProfile
import dev.jordond.filmstrip.picture.fixtures.iccp
import dev.jordond.filmstrip.picture.fixtures.jpeg
import dev.jordond.filmstrip.picture.fixtures.jpegSegments
import dev.jordond.filmstrip.picture.fixtures.png
import dev.jordond.filmstrip.picture.fixtures.sizedProfile
import dev.jordond.filmstrip.picture.fixtures.storedZlib
import dev.jordond.filmstrip.picture.fixtures.tiff
import dev.jordond.filmstrip.picture.fixtures.tiffHeader
import dev.jordond.filmstrip.picture.fixtures.webp
import dev.jordond.filmstrip.picture.png.DYNAMIC_ZLIB
import dev.jordond.filmstrip.picture.png.FIXED_ZLIB
import dev.jordond.filmstrip.picture.png.hex
import dev.jordond.filmstrip.picture.png.lettersProfile
import dev.jordond.filmstrip.picture.png.periodicProfile
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

/**
 * Reads the ICC profile out of synthetic PNG, JPEG, WebP, ISOBMFF and TIFF containers.
 *
 * Each container is built byte by byte around a known profile, so a read that returns anything but exactly those
 * bytes is wrong.
 */
class EmbeddedProfileTest {
  @Test
  fun `a png profile inflates from stored blocks`() {
    embeddedProfile(png(iccp(storedZlib(PROFILE, splitAt = 100)))) shouldBe PROFILE
  }

  @Test
  fun `a png profile inflates from huffman blocks`() {
    embeddedProfile(png(iccp(hex(DYNAMIC_ZLIB)))) shouldBe lettersProfile()
    embeddedProfile(png(iccp(hex(FIXED_ZLIB)))) shouldBe periodicProfile()
  }

  @Test
  fun `a png without iccp or with it after the image data has no profile`() {
    embeddedProfile(png()).shouldBeNull()
    embeddedProfile(png(afterImageData = iccp(storedZlib(PROFILE)))).shouldBeNull()
  }

  @Test
  fun `a png profile whose checksum is wrong is refused`() {
    val stream = storedZlib(PROFILE)
    stream[stream.lastIndex] = (stream.last() + 1).toByte()
    embeddedProfile(png(iccp(stream))).shouldBeNull()
  }

  @Test
  fun `a png iccp chunk whose crc is wrong is refused`() {
    val chunk = iccp(storedZlib(PROFILE))
    chunk[chunk.lastIndex] = (chunk.last() + 1).toByte()
    embeddedProfile(png(chunk)).shouldBeNull()
  }

  @Test
  fun `a png profile must inflate to the size its header declares`() {
    val longer = PROFILE + byteArrayOf(0)
    val shorter = PROFILE.copyOf(PROFILE.size - 1)
    val small = sizedProfile(100)
    embeddedProfile(png(iccp(storedZlib(longer)))).shouldBeNull()
    embeddedProfile(png(iccp(storedZlib(shorter)))).shouldBeNull()
    embeddedProfile(png(iccp(storedZlib(small)))).shouldBeNull()
    embeddedProfile(png(iccp(storedZlib(sizedProfile(128))))) shouldBe sizedProfile(128)
  }

  @Test
  fun `a jpeg profile split over two app2 segments is joined`() {
    embeddedProfile(jpeg(PROFILE)) shouldBe PROFILE
  }

  @Test
  fun `a jpeg profile split over the most app2 segments a jpeg allows is joined`() {
    val bounds = (0..255).map { it * PROFILE.size / 255 }
    val segments = (1..255).map { app2(it, 255, PROFILE.copyOfRange(bounds[it - 1], bounds[it])) }
    embeddedProfile(jpegSegments(*segments.reversed().toTypedArray())) shouldBe PROFILE
  }

  @Test
  fun `jpeg profile segments join in sequence order and a missing one gives no profile`() {
    embeddedProfile(jpegSegments(app2(2, 2, SECOND_HALF), app2(1, 2, FIRST_HALF))) shouldBe PROFILE
    embeddedProfile(jpegSegments(app2(1, 2, FIRST_HALF))).shouldBeNull()
    embeddedProfile(jpegSegments()).shouldBeNull()
  }

  @Test
  fun `a jpeg profile segment repeated gives no profile`() {
    val segments = jpegSegments(app2(1, 2, FIRST_HALF), app2(1, 2, FIRST_HALF), app2(2, 2, SECOND_HALF))
    embeddedProfile(segments).shouldBeNull()
  }

  @Test
  fun `jpeg profile segments that disagree on their count give no profile`() {
    embeddedProfile(jpegSegments(app2(1, 2, FIRST_HALF), app2(2, 3, SECOND_HALF))).shouldBeNull()
  }

  @Test
  fun `a webp profile comes from the iccp chunk when vp8x flags one`() {
    embeddedProfile(webp(PROFILE)) shouldBe PROFILE
    embeddedProfile(webp(PROFILE, flagged = false)).shouldBeNull()
    embeddedProfile(webp(null)).shouldBeNull()
  }

  @Test
  fun `a webp flagged with a profile but without an iccp chunk has no profile`() {
    embeddedProfile(webp(null, flagged = true)).shouldBeNull()
  }

  @Test
  fun `a webp iccp chunk past the riff size is not read`() {
    embeddedProfile(webp(PROFILE, riffSize = 4L + 18 + 12)).shouldBeNull()
    embeddedProfile(webp(PROFILE, riffSize = 0xFFFFFFF0L)) shouldBe PROFILE
  }

  @Test
  fun `an empty profile gives no profile`() {
    val empty = ByteArray(0)
    embeddedProfile(jpegSegments(app2(1, 1, empty))).shouldBeNull()
    embeddedProfile(webp(empty)).shouldBeNull()
    embeddedProfile(avif(empty)).shouldBeNull()
    embeddedProfile(tiff(empty, little = true)).shouldBeNull()
  }

  @Test
  fun `a tiff profile is tag 34675 in either byte order and in bigtiff`() {
    embeddedProfile(tiff(PROFILE, little = false)) shouldBe PROFILE
    embeddedProfile(tiff(PROFILE, little = true)) shouldBe PROFILE
    embeddedProfile(bigTiff(PROFILE, little = true)) shouldBe PROFILE
    embeddedProfile(bigTiff(PROFILE, little = false)) shouldBe PROFILE
    embeddedProfile(tiff(null, little = true)).shouldBeNull()
  }

  @Test
  fun `a tiff profile of four bytes or less sits inline in its entry`() {
    val four = byteArrayOf(1, 2, 3, 4)
    val three = byteArrayOf(5, 6, 7)
    embeddedProfile(tiff(four, little = true)) shouldBe four
    embeddedProfile(tiff(four, little = false)) shouldBe four
    embeddedProfile(tiff(three, little = true)) shouldBe three
  }

  @Test
  fun `a profile over 16 MiB is refused`() {
    embeddedProfile(tiffHeader(MAX_PROFILE, little = true) + ByteArray(MAX_PROFILE))?.size shouldBe MAX_PROFILE
    embeddedProfile(tiffHeader(MAX_PROFILE + 1, little = true) + ByteArray(MAX_PROFILE + 1)).shouldBeNull()
  }

  @Test
  fun `bytes in no known container have no profile`() {
    embeddedProfile(ByteArray(0)).shouldBeNull()
    embeddedProfile(asciiBytes("GIF89a")).shouldBeNull()
  }

  @Test
  fun `a container cut inside its profile gives no profile and never throws`() {
    for ((tagged, profile) in taggedContainers()) {
      embeddedProfile(tagged) shouldBe profile
      embeddedProfile(tagged.copyOf(maxOf(0, tagged.size - profile.size))).shouldBeNull()
      for (length in tagged.indices) {
        val read = embeddedProfile(tagged.copyOf(length))
        if (read != null) read shouldBe profile
      }
    }
  }

  @Test
  fun `a corrupted container never throws`() {
    val random = Random(7)
    for ((tagged, _) in taggedContainers()) {
      repeat(500) {
        val corrupted = tagged.copyOf()
        repeat(4) { corrupted[random.nextInt(corrupted.size)] = random.nextInt(256).toByte() }
        embeddedProfile(corrupted)
      }
    }
  }

  @Test
  fun `a corrupted png profile stream never throws and stays within the size cap`() {
    val random = Random(13)
    for (stream in listOf(storedZlib(PROFILE), hex(DYNAMIC_ZLIB), hex(FIXED_ZLIB))) {
      repeat(500) {
        val corrupted = stream.copyOf()
        repeat(1 + it % 4) { corrupted[2 + random.nextInt(corrupted.size - 2)] = random.nextInt(256).toByte() }
        val read = embeddedProfile(png(iccp(corrupted)))
        (read == null || read.size <= MAX_PROFILE) shouldBe true
      }
    }
  }

  private fun taggedContainers(): List<Pair<ByteArray, ByteArray>> =
    listOf(
      png(iccp(storedZlib(PROFILE))) to PROFILE,
      png(iccp(hex(DYNAMIC_ZLIB))) to lettersProfile(),
      png(iccp(hex(FIXED_ZLIB))) to periodicProfile(),
      jpeg(PROFILE) to PROFILE,
      webp(PROFILE) to PROFILE,
      avif(PROFILE, trailingAssociations = false) to PROFILE,
      avif(PROFILE, wideIndex = true, trailingAssociations = false) to PROFILE,
      avif(PROFILE, version = 1, trailingAssociations = false) to PROFILE,
      avif(null, second = PROFILE, grid = true, trailingAssociations = false) to PROFILE,
      avif(null, second = PROFILE, grid = true, version = 1, trailingAssociations = false) to PROFILE,
      tiff(PROFILE, little = true) to PROFILE,
      bigTiff(PROFILE, little = true) to PROFILE,
    )
}
