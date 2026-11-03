package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.heifAlpha
import dev.jordond.filmstrip.picture.heifPrimaryIsTiled
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Core's read of the alpha item an AVIF or HEIC carries for its primary item, on the stills [HeifStills] writes with
 * the [AlphaPlanes] beside them.
 */
class HeifAlphaTest {
  @Test
  fun `a still with no alpha item has none`() {
    heifAlpha(ColorPictures.NCLX_UNSPECIFIED.bytes()).shouldBeNull()
    heifAlpha(ColorPictures.NCLX_UNSPECIFIED.gridBytes()).shouldBeNull()
    heifAlpha(byteArrayOf(1, 2, 3)).shouldBeNull()
  }

  @Test
  fun `an AVIF and a HEIC alpha item are read by their id`() {
    for ((item, alpha) in listOf(AVIF to AlphaPlanes.avif(), HEIC to AlphaPlanes.heic())) {
      val read = withClue(item.itemType) { heifAlpha(still(item, alpha)).shouldNotBeNull() }
      read.item shouldBe 2L
      read.grid.shouldBeNull()
      read.premultiplied shouldBe false
      read.fullRange shouldBe true
      read.bitDepth shouldBe 8
    }
  }

  @Test
  fun `a limited range AVIF and HEIC alpha item read their range off their own codestream`() {
    for ((item, alpha) in listOf(AVIF to AlphaPlanes.avif(fullRange = false), HEIC to AlphaPlanes.heic(false, false))) {
      val read = withClue(item.itemType) { heifAlpha(still(item, alpha)).shouldNotBeNull() }
      withClue(item.itemType) { read.fullRange shouldBe false }
      withClue(item.itemType) { read.bitDepth shouldBe 8 }
    }
  }

  @Test
  fun `the alpha item follows a thumbnail written ahead of the primary item`() {
    val bytes = still(AVIF, AlphaPlanes.avif(), thumbnail = ColorPictures.NCLX_BT709_LIMITED.thumbnail())
    heifAlpha(bytes).shouldNotBeNull().item shouldBe 3L
  }

  @Test
  fun `a premultiplied alpha item says so`() {
    heifAlpha(still(AVIF, AlphaPlanes.avif(premultiplied = true))).shouldNotBeNull().premultiplied shouldBe true
  }

  @Test
  fun `an alpha grid is read as its tiles in rows and the size it crops to`() {
    val bytes =
      HeifStills.grid(
        "avif",
        AVIF.itemType,
        AVIF.configuration,
        AVIF.codestream,
        AVIF.size,
        columns = 3,
        rows = 2,
        size = Size(20, 12),
        alpha = List(6) { AlphaPlanes.avifTile(it) },
      )
    heifPrimaryIsTiled(bytes) shouldBe true
    val alpha = heifAlpha(bytes).shouldNotBeNull()
    alpha.item shouldBe 8L
    val grid = alpha.grid.shouldNotBeNull()
    grid.tiles shouldBe (9L..14L).toList()
    grid.columns shouldBe 3
    grid.rows shouldBe 2
    grid.size shouldBe Size(20, 12)
    alpha.fullRange shouldBe true
  }

  @Test
  fun `an auxiliary item of another type is not an alpha item`() {
    val depth = AlphaPlanes.avif()
    val bytes = still(AVIF, depth)
    val alphaType = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".encodeToByteArray()
    val depthType = "urn:mpeg:mpegB:cicp:systems:auxiliary:depth".encodeToByteArray()
    val at = bytes.indexOf(alphaType)
    alphaType.indices.forEach { bytes[at + it] = depthType[it] }
    heifAlpha(bytes).shouldBeNull()
  }

  // The alpha item's entry comes last in the ipma, and claiming one more association than it holds runs it past the
  // end of the box, so the still has no alpha.
  @Test
  fun `an alpha item whose ipma entry runs past the box has none`() {
    val bytes = still(AVIF, AlphaPlanes.avif())
    val ipma = bytes.indexOf("ipma".encodeToByteArray())
    val ipmaEnd = ipma - 4 + (0 until 4).fold(0) { size, at -> (size shl 8) or (bytes[ipma - 4 + at].toInt() and 0xFF) }
    val primaryCount = ipma + 4 + 4 + 4 + 2
    val alphaCount = primaryCount + 1 + bytes[primaryCount] + 2
    bytes[alphaCount].toInt() shouldBe 4
    alphaCount + 1 + 4 shouldBe ipmaEnd
    heifAlpha(bytes).shouldNotBeNull()

    bytes[alphaCount] = 5
    heifAlpha(bytes).shouldBeNull()
  }

  private fun still(
    item: HeifThumbnail,
    alpha: HeifAlphaPlane,
    thumbnail: HeifThumbnail? = null,
  ): ByteArray {
    val brand = if (item.itemType == "av01") "avif" else "heic"
    return HeifStills.still(
      brand,
      item.itemType,
      item.configuration,
      item.codestream,
      item.size,
      thumbnail = thumbnail,
      alpha = alpha,
    )
  }

  private fun ByteArray.indexOf(part: ByteArray): Int =
    (0..size - part.size).first { start -> part.indices.all { this[start + it] == part[it] } }

  private companion object {
    val AVIF = ColorPictures.NCLX_UNSPECIFIED.thumbnail()
    val HEIC = ColorPictures.HEIC_NCLX_BT709_FULL.thumbnail()
  }
}
