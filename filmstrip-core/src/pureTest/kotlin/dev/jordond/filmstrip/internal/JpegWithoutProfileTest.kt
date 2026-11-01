package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.TestIccProfiles
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Which segments [jpegWithoutProfile] leaves out, one case per layout a writer produces.
 */
class JpegWithoutProfileTest {
  @Test
  fun `every ICC segment is left out and everything else is kept`() {
    for ((name, jpeg, expected) in CASES) {
      withClue(name) { jpegWithoutProfile(jpeg).toList() shouldBe expected.toList() }
    }
  }

  @Test
  fun `the profile left out is the one core reads`() {
    val profile = TestIccProfiles.displayP3
    val jpeg = jpeg(profile)

    pictureColour(jpeg).shouldNotBeNull()
    pictureColour(jpegWithoutProfile(jpeg)).shouldBeNull()
    jpegWithoutProfile(jpeg).size shouldBe jpeg.size - 2 * (4 + 14) - profile.size
  }

  private companion object {
    val START = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    val SCAN = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2, 0x55, 0xFF.toByte(), 0xD9.toByte())
    val JFIF = segment(0xE0, ascii("JFIF") + byteArrayOf(0, 1, 2, 0, 0, 1, 0, 1, 0, 0))
    val EXIF = segment(0xE1, ascii("Exif") + byteArrayOf(0, 0, 0x4D, 0x4D))
    val MPF = segment(0xE2, ascii("MPF") + byteArrayOf(0, 0x4D, 0x4D, 0, 0x2A))
    val FIRST_HALF = app2(1, 2, byteArrayOf(1, 2, 3, 4))
    val SECOND_HALF = app2(2, 2, byteArrayOf(5, 6, 7))
    val FILL = byteArrayOf(0xFF.toByte(), 0xFF.toByte())

    // A segment whose length runs past the end of the picture, and one whose length cannot hold itself.
    val TRUNCATED = byteArrayOf(0xFF.toByte(), 0xE2.toByte(), 0x7F, 0x00) + ascii("ICC_PROFILE")
    val ZERO_LENGTH = byteArrayOf(0xFF.toByte(), 0xE2.toByte(), 0, 0)

    val ZERO_LENGTH_JPEG = START + ZERO_LENGTH + FIRST_HALF
    val NOT_A_JPEG = byteArrayOf(0x89.toByte(), 0x50) + FIRST_HALF

    val CASES =
      listOf(
        Triple(
          "a profile split over two APP2 segments",
          START + JFIF + FIRST_HALF + SECOND_HALF + SCAN,
          START + JFIF + SCAN,
        ),
        Triple("an APP2 profile after an APP1", START + JFIF + EXIF + FIRST_HALF + SCAN, START + JFIF + EXIF + SCAN),
        Triple("an MPF APP2 beside the profile", START + MPF + FIRST_HALF + SECOND_HALF + SCAN, START + MPF + SCAN),
        Triple("fill bytes ahead of the profile", START + JFIF + FILL + FIRST_HALF + SCAN, START + JFIF + FILL + SCAN),
        Triple(
          "a truncated segment after the profile",
          START + FIRST_HALF + TRUNCATED + SCAN,
          START + TRUNCATED + SCAN,
        ),
        Triple("a zero length segment ahead of the profile", ZERO_LENGTH_JPEG, ZERO_LENGTH_JPEG),
        Triple("a JPEG with no profile", START + JFIF + SCAN, START + JFIF + SCAN),
        Triple("a picture that is not a JPEG", NOT_A_JPEG, NOT_A_JPEG),
      )

    fun segment(
      marker: Int,
      payload: ByteArray,
    ): ByteArray =
      byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) +
        payload
  }
}
