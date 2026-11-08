package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.HeifCodec
import dev.jordond.filmstrip.picture.heif.HeifCodedImage
import dev.jordond.filmstrip.picture.heif.HeifGridRead
import dev.jordond.filmstrip.picture.heif.HeifOverlayRead
import dev.jordond.filmstrip.picture.heif.heifCodedImages
import dev.jordond.filmstrip.picture.heif.heifPrimaryGrid
import dev.jordond.filmstrip.picture.heif.heifPrimaryOverlay
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlin.test.Test

/**
 * The data and decoder configuration of each coded image a HEIF grid or overlay is put together from, read back
 * through core against what the generator wrote.
 */
class HeifCodedImagesTest {
  private val flat = ColorPictures.NCLX_BT709_LIMITED.thumbnail()
  private val edge = ColorPictures.NCLX_BT709_FULL_EDGE_CHROMA.thumbnail()

  @Test
  fun aGridsMiddleTileCarriesTheBytesAndConfigurationWrittenForIt() {
    val images = List(9) { if (it == 4) edge else flat }
    val bytes =
      HeifStills.grid(
        "avif",
        flat.itemType,
        flat.configuration,
        flat.codestream,
        flat.size,
        3,
        3,
        images = images,
      )
    val grid = heifPrimaryGrid(bytes).shouldBeInstanceOf<HeifGridRead.Read>().grid
    val coded = heifCodedImages(bytes, grid.tiles).shouldNotBeNull()
    coded.map { it.item } shouldBe grid.tiles
    coded[4].shouldBeWritten(edge, HeifCodec.Av1)
    coded[3].shouldBeWritten(flat, HeifCodec.Av1)
    coded[5].shouldBeWritten(flat, HeifCodec.Av1)
  }

  @Test
  fun anOverlaysInputsCarryTheirOwnImagesInInputOrder() {
    val heic = ColorPictures.HEIC_NCLX_BT709_LIMITED.thumbnail()
    val images = listOf(edge, heic, flat)
    val bytes = HeifOverlays.overlay("heic", flat, listOf(0 to 0, 8 to 0, 0 to 8), Size(16, 16), images = images)
    val overlay = heifPrimaryOverlay(bytes).shouldBeInstanceOf<HeifOverlayRead.Read>().overlay
    val coded = heifCodedImages(bytes, overlay.inputs.map { it.item }).shouldNotBeNull()
    coded[0].shouldBeWritten(edge, HeifCodec.Av1)
    coded[1].shouldBeWritten(heic, HeifCodec.Hevc)
    coded[2].shouldBeWritten(flat, HeifCodec.Av1)
  }

  @Test
  fun anItemNamedOverAndOverIsReadOnceAndSharedByEveryPlaceItIsNamed() {
    val bytes = HeifStills.grid("avif", flat.itemType, flat.configuration, flat.codestream, flat.size)
    val grid = heifPrimaryGrid(bytes).shouldBeInstanceOf<HeifGridRead.Read>().grid
    val (first, second) = grid.tiles
    val coded = heifCodedImages(bytes, List(MAX_GRID_TILES) { if (it % 2 == 0) first else second }).shouldNotBeNull()
    coded.size shouldBe MAX_GRID_TILES
    coded.forEachIndexed { at, image -> image shouldBeSameInstanceAs coded[at % 2] }
    coded[0].sample shouldNotBeSameInstanceAs coded[1].sample
  }

  @Test
  fun anItemThatIsNotACodedImageReadsAsNone() {
    val bytes = HeifStills.grid("avif", flat.itemType, flat.configuration, flat.codestream, flat.size)
    val grid = heifPrimaryGrid(bytes).shouldBeInstanceOf<HeifGridRead.Read>().grid
    heifCodedImages(bytes, listOf(grid.tiles.first() - 1)).shouldBeNull()
    heifCodedImages(byteArrayOf(1, 2, 3), grid.tiles).shouldBeNull()
  }

  @Test
  fun anExtentListedMoreThanOnceReadsOverAndOverUpToTheFilesSize() {
    val tile = { extents: Int ->
      HeifStills.grid(
        "avif",
        flat.itemType,
        flat.configuration,
        flat.codestream,
        flat.size,
        1,
        1,
        tileExtents = extents,
      )
    }
    val read = { bytes: ByteArray ->
      val grid = heifPrimaryGrid(bytes).shouldBeInstanceOf<HeifGridRead.Read>().grid
      heifCodedImages(bytes, grid.tiles)?.single()?.sample?.toList()
    }
    read(tile(3)) shouldBe List(3) { flat.codestream.toList() }.flatten()
    // Each extent more grows the file by one iloc entry and the sample by one codestream, so the sample passes the
    // file's size between the most extents that fit and one more.
    val entry = tile(2).size - tile(1).size
    val most = (tile(1).size - entry) / (flat.codestream.size - entry)
    withClue("the most that fit, $most") {
      tile(most).size shouldBeGreaterThanOrEqual most * flat.codestream.size
      read(tile(most)).shouldNotBeNull().size shouldBe most * flat.codestream.size
    }
    withClue("one more, ${most + 1}") {
      tile(most + 1).size shouldBeLessThan (most + 1) * flat.codestream.size
      read(tile(most + 1)).shouldBeNull()
    }
  }

  private fun HeifCodedImage.shouldBeWritten(
    image: HeifThumbnail,
    codec: HeifCodec,
  ) {
    this.codec shouldBe codec
    sample.toList() shouldBe image.codestream.toList()
    configuration.toList() shouldBe image.configuration.copyOfRange(BOX_HEADER_BYTES, image.configuration.size).toList()
  }

  private companion object {
    const val BOX_HEADER_BYTES = 8

    // The most tiles a grid lists, 256 a side.
    const val MAX_GRID_TILES = 65536
  }
}
