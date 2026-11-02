package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.internal.heifPrimaryIsTiled
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HEIF stills written with a thumbnail item ahead of their primary item, read back through core's own readers.
 */
class HeifThumbnailTest {
  private val heic = ColourPictures.HEIC_NCLX_BT709_LIMITED
  private val avif = ColourPictures.NCLX_BT709_LIMITED

  @Test
  fun aGridReadsAsTiledWithOrWithoutAThumbnail() {
    for (grid in listOf(heic.gridBytes(), heic.gridBytes(heic.thumbnail()), avif.gridBytes(avif.thumbnail()))) {
      assertTrue(heifPrimaryIsTiled(grid))
    }
  }

  @Test
  fun aCodedPrimaryItemReadsAsNotTiledWithOrWithoutAThumbnail() {
    val stills = listOf(heic.bytes(), heic.bytes(avif.thumbnail()), avif.bytes(), avif.bytes(heic.thumbnail()))
    for (still in stills) assertFalse(heifPrimaryIsTiled(still))
    assertFalse(heifPrimaryIsTiled(heic.gridBytes().copyOf(TRUNCATED)), "a truncated grid")
    assertFalse(heifPrimaryIsTiled(PNG_SIGNATURE), "a PNG")
  }

  // A profile is compared by identity, so only the stills described by code points or by nothing are held equal.
  @Test
  fun aThumbnailLeavesThePrimaryItemsColourWhereCoreReadsIt() {
    for (picture in ColourPictures.HEICS_WITH_SIGNAL_VARIANTS.filter { it.profile == null }) {
      val thumbnail = avif.thumbnail()
      assertEquals(pictureColour(picture.bytes()), pictureColour(picture.bytes(thumbnail)), "$picture")
      assertEquals(pictureColour(picture.gridBytes()), pictureColour(picture.gridBytes(thumbnail)), "tiled $picture")
      assertEquals(pictureYcbcrCoding(picture.bytes()), pictureYcbcrCoding(picture.bytes(thumbnail)), "$picture")
    }
  }

  @Test
  fun aHeicWithAThumbnailStatesItsNclxInItsCodestreamOnceRewritten() {
    for (picture in ColourPictures.HEICS.filter { it.nclx != null }) {
      val nclx = checkNotNull(picture.nclx)
      for (bytes in listOf(picture.bytes(avif.thumbnail()), picture.gridBytes(heic.thumbnail()))) {
        val coding = pictureYcbcrCoding(heifWithExplicitCodestreamColour(bytes).handed)
        assertEquals(nclx.matrix, coding?.codestream?.matrix, "$picture matrix")
        assertEquals(nclx.fullRange, coding?.codestream?.fullRange, "$picture range")
      }
    }
  }

  private companion object {
    const val TRUNCATED = 40
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  }
}
