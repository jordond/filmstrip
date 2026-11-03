package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import dev.jordond.filmstrip.picture.heifPrimaryIsTiled
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * HEIF stills written with a thumbnail item ahead of their primary item, read back through core's own readers.
 */
class HeifThumbnailTest {
  private val heic = ColorPictures.HEIC_NCLX_BT709_LIMITED
  private val avif = ColorPictures.NCLX_BT709_LIMITED

  @Test
  fun aGridReadsAsTiledWithOrWithoutAThumbnail() {
    for (grid in listOf(heic.gridBytes(), heic.gridBytes(heic.thumbnail()), avif.gridBytes(avif.thumbnail()))) {
      heifPrimaryIsTiled(grid) shouldBe true
    }
  }

  @Test
  fun aCodedPrimaryItemReadsAsNotTiledWithOrWithoutAThumbnail() {
    val stills = listOf(heic.bytes(), heic.bytes(avif.thumbnail()), avif.bytes(), avif.bytes(heic.thumbnail()))
    for (still in stills) heifPrimaryIsTiled(still) shouldBe false
    withClue("a truncated grid") { heifPrimaryIsTiled(heic.gridBytes().copyOf(TRUNCATED)) shouldBe false }
    withClue("a PNG") { heifPrimaryIsTiled(PNG_SIGNATURE) shouldBe false }
  }

  // A profile is compared by identity, so only the stills described by code points or by nothing are held equal.
  @Test
  fun aThumbnailLeavesThePrimaryItemsColorWhereCoreReadsIt() {
    for (picture in ColorPictures.HEICS_WITH_SIGNAL_VARIANTS.filter { it.profile == null }) {
      val thumbnail = avif.thumbnail()
      withClue("$picture") { pictureColor(picture.bytes(thumbnail)) shouldBe pictureColor(picture.bytes()) }
      withClue("tiled $picture") {
        pictureColor(picture.gridBytes(thumbnail)) shouldBe pictureColor(picture.gridBytes())
      }
      withClue("$picture") { pictureYcbcrCoding(picture.bytes(thumbnail)) shouldBe pictureYcbcrCoding(picture.bytes()) }
    }
  }

  @Test
  fun aHeicWithAThumbnailStatesItsNclxInItsCodestreamOnceRewritten() {
    for (picture in ColorPictures.HEICS.filter { it.nclx != null }) {
      val nclx = checkNotNull(picture.nclx)
      for (bytes in listOf(picture.bytes(avif.thumbnail()), picture.gridBytes(heic.thumbnail()))) {
        val coding = pictureYcbcrCoding(heifWithExplicitCodestreamColor(bytes).handed)
        withClue("$picture matrix") { coding?.codestream?.matrix shouldBe nclx.matrix }
        withClue("$picture range") { coding?.codestream?.fullRange shouldBe nclx.fullRange }
      }
    }
  }

  private companion object {
    const val TRUNCATED = 40
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  }
}
