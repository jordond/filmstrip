package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.heifAlpha
import dev.jordond.filmstrip.internal.heifPrimaryIsTiled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Core's read of the alpha item an AVIF or HEIC carries for its primary item, on the stills [HeifStills] writes with
 * the [AlphaPlanes] beside them.
 */
class HeifAlphaTest {
  @Test
  fun `a still with no alpha item has none`() {
    assertNull(heifAlpha(ColourPictures.NCLX_UNSPECIFIED.bytes()))
    assertNull(heifAlpha(ColourPictures.NCLX_UNSPECIFIED.gridBytes()))
    assertNull(heifAlpha(byteArrayOf(1, 2, 3)))
  }

  @Test
  fun `an AVIF and a HEIC alpha item are read by their id`() {
    for ((item, alpha) in listOf(AVIF to AlphaPlanes.avif(), HEIC to AlphaPlanes.heic())) {
      val read = assertNotNull(heifAlpha(still(item, alpha)), item.itemType)
      assertEquals(2L, read.item)
      assertNull(read.grid)
      assertFalse(read.premultiplied)
      assertTrue(read.fullRange)
      assertEquals(8, read.bitDepth)
    }
  }

  @Test
  fun `a limited range AVIF and HEIC alpha item read their range off their own codestream`() {
    for ((item, alpha) in listOf(AVIF to AlphaPlanes.avif(fullRange = false), HEIC to AlphaPlanes.heic(false, false))) {
      val read = assertNotNull(heifAlpha(still(item, alpha)), item.itemType)
      assertFalse(read.fullRange, item.itemType)
      assertEquals(8, read.bitDepth, item.itemType)
    }
  }

  @Test
  fun `the alpha item follows a thumbnail written ahead of the primary item`() {
    val bytes = still(AVIF, AlphaPlanes.avif(), thumbnail = ColourPictures.NCLX_BT709_LIMITED.thumbnail())
    assertEquals(3L, assertNotNull(heifAlpha(bytes)).item)
  }

  @Test
  fun `a premultiplied alpha item says so`() {
    assertTrue(assertNotNull(heifAlpha(still(AVIF, AlphaPlanes.avif(premultiplied = true)))).premultiplied)
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
    assertTrue(heifPrimaryIsTiled(bytes))
    val alpha = assertNotNull(heifAlpha(bytes))
    assertEquals(8L, alpha.item)
    val grid = assertNotNull(alpha.grid)
    assertEquals((9L..14L).toList(), grid.tiles)
    assertEquals(3, grid.columns)
    assertEquals(2, grid.rows)
    assertEquals(Size(20, 12), grid.size)
    assertTrue(alpha.fullRange)
  }

  @Test
  fun `an auxiliary item of another type is not an alpha item`() {
    val depth = AlphaPlanes.avif()
    val bytes = still(AVIF, depth)
    val alphaType = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".encodeToByteArray()
    val depthType = "urn:mpeg:mpegB:cicp:systems:auxiliary:depth".encodeToByteArray()
    val at = bytes.indexOf(alphaType)
    alphaType.indices.forEach { bytes[at + it] = depthType[it] }
    assertNull(heifAlpha(bytes))
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
    val AVIF = ColourPictures.NCLX_UNSPECIFIED.thumbnail()
    val HEIC = ColourPictures.HEIC_NCLX_BT709_FULL.thumbnail()
  }
}
