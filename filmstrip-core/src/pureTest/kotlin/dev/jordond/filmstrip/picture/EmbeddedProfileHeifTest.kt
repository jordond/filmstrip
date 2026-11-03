package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.picture.fixtures.BoxSize
import dev.jordond.filmstrip.picture.fixtures.NCLX
import dev.jordond.filmstrip.picture.fixtures.PROFILE
import dev.jordond.filmstrip.picture.fixtures.avif
import dev.jordond.filmstrip.picture.fixtures.colr
import dev.jordond.filmstrip.picture.fixtures.embeddedProfile
import dev.jordond.filmstrip.picture.fixtures.heif
import dev.jordond.filmstrip.picture.fixtures.indexedAvif
import dev.jordond.filmstrip.picture.fixtures.ipma
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Reads the ICC profile out of synthetic AVIF and HEIF files.
 *
 * Each file is built box by box around a known profile, with other items, extra `ipma` boxes and grid tiles tagged
 * with different profiles, so a read that follows the wrong association returns the wrong bytes.
 */
class EmbeddedProfileHeifTest {
  @Test
  fun `an avif profile belongs to the primary item`() {
    val thumbnail = PROFILE.reversedArray()
    embeddedProfile(avif(PROFILE, second = thumbnail)) shouldBe PROFILE
    embeddedProfile(avif(PROFILE, second = thumbnail, wideIndex = true)) shouldBe PROFILE
    embeddedProfile(avif(PROFILE, kind = "rICC")) shouldBe PROFILE
    embeddedProfile(avif(null, second = thumbnail)).shouldBeNull()
  }

  @Test
  fun `an avif without a primary item has no profile`() {
    embeddedProfile(avif(PROFILE, second = PROFILE.reversedArray(), primaryItem = false)).shouldBeNull()
  }

  @Test
  fun `an avif with version 1 item boxes reads 32 bit item ids`() {
    embeddedProfile(avif(PROFILE, second = PROFILE.reversedArray(), version = 1)) shouldBe PROFILE
    embeddedProfile(avif(null, second = PROFILE, grid = true, version = 1)) shouldBe PROFILE
  }

  @Test
  fun `a grid primary item without a profile takes the one on its tiles`() {
    embeddedProfile(avif(null, second = PROFILE, grid = true)) shouldBe PROFILE
  }

  @Test
  fun `a grid primary item with its own profile keeps it over its tiles`() {
    embeddedProfile(avif(PROFILE, second = PROFILE.reversedArray(), grid = true)) shouldBe PROFILE
  }

  @Test
  fun `every ipma box is read and the first association of an item wins`() {
    val thumbnail = PROFILE.reversedArray()
    val properties = listOf(colr("prof", thumbnail), NCLX, colr("prof", PROFILE))
    val thumbnailOnly = ipma(listOf(2 to listOf(1)))
    val primaryOnly = ipma(listOf(1 to listOf(2, 3)), version = 1, wideIndex = true)
    embeddedProfile(heif(properties, thumbnailOnly, primaryOnly)) shouldBe PROFILE
    val primaryFirst = ipma(listOf(1 to listOf(3)))
    val primaryLater = ipma(listOf(1 to listOf(1)), version = 1)
    embeddedProfile(heif(properties, primaryFirst, primaryLater)) shouldBe PROFILE
  }

  @Test
  fun `property indices past the first 64 read from the right word`() {
    embeddedProfile(indexedAvif(200, profileAt = 70, associated = listOf(6, 70))) shouldBe PROFILE
    embeddedProfile(indexedAvif(200, profileAt = 70, associated = listOf(6, 70), wideIndex = true)) shouldBe PROFILE
    embeddedProfile(indexedAvif(200, profileAt = 130, associated = listOf(2, 66, 130))) shouldBe PROFILE
    embeddedProfile(indexedAvif(200, profileAt = 130, associated = listOf(2, 66))).shouldBeNull()
  }

  @Test
  fun `only the first 32767 properties can hold a profile`() {
    embeddedProfile(indexedAvif(32767, profileAt = 32767, associated = listOf(63, 32767))) shouldBe PROFILE
    embeddedProfile(indexedAvif(32768, profileAt = 32768, associated = listOf(32767))).shouldBeNull()
  }

  @Test
  fun `a grid without its own profile takes the one on its listed tile`() {
    val properties = listOf(NCLX, colr("prof", PROFILE))
    val items = listOf(1 to listOf(1), 4 to listOf(1), 6 to listOf(2), 9 to listOf(1))
    embeddedProfile(heif(properties, ipma(items), tiles = listOf(9, 4, 6, 4))) shouldBe PROFILE
  }

  @Test
  fun `a grid takes the profile of the first listed tile that has one`() {
    val other = PROFILE.reversedArray()
    val properties = listOf(colr("prof", PROFILE), colr("prof", other), NCLX)
    val items = listOf(1 to listOf(3), 4 to listOf(2), 6 to listOf(3), 9 to listOf(1))
    embeddedProfile(heif(properties, ipma(items), tiles = listOf(9, 4, 6))) shouldBe PROFILE
    embeddedProfile(heif(properties, ipma(items), tiles = listOf(6, 4, 9))) shouldBe other
  }

  @Test
  fun `a box sized zero runs to the end of its parent`() {
    val properties = listOf(NCLX, colr("prof", PROFILE))
    embeddedProfile(heif(properties, ipma(listOf(1 to listOf(2))), metaSize = BoxSize.ToEnd)) shouldBe PROFILE
  }

  @Test
  fun `a box with a 64 bit size is read past its long header`() {
    val properties = listOf(NCLX, colr("prof", PROFILE))
    embeddedProfile(heif(properties, ipma(listOf(1 to listOf(2))), metaSize = BoxSize.Large)) shouldBe PROFILE
  }
}
