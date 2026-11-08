package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.heif.ASSEMBLY_TEXEL_BYTES
import dev.jordond.filmstrip.picture.heif.GRID_TOO_LARGE_REASON
import dev.jordond.filmstrip.picture.heif.HeifGrid
import dev.jordond.filmstrip.picture.heif.HeifGridRead
import dev.jordond.filmstrip.picture.heif.heifAlpha
import dev.jordond.filmstrip.picture.heif.heifPrimaryGrid
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * HEIF grids held under the picture ceiling, read back through core.
 */
class HeifAssemblyTest {
  private val tile = ColorPictures.NCLX_BT709_LIMITED.thumbnail()

  @Test
  fun aGridsPeakCountsEveryTileAndTheCroppedPicture() {
    val grid = read(grid(tile.size, columns = 3, rows = 2, size = Size(20, 12)))
    grid.tileSizes shouldBe List(6) { Size(8, 8) }
    grid.peakBytes shouldBe (6L * 8 * 8 + 20 * 12) * ASSEMBLY_TEXEL_BYTES
  }

  @Test
  fun aGridAtTheCeilingReadsAndOneRowOfTilesPastIsRefused() {
    val width = 16384
    // Two tiles in one column and the picture they make, as tall as both, held together.
    val rows = (PICTURE_PEAK_CEILING / (ASSEMBLY_TEXEL_BYTES * width) / 4).toInt()
    val at = { high: Int -> grid(Size(width, high), columns = 1, rows = 2) }
    read(at(rows)).peakBytes shouldBe PICTURE_PEAK_CEILING
    heifPrimaryGrid(at(rows + 1)).shouldBeInstanceOf<HeifGridRead.Refused>().reason shouldBe GRID_TOO_LARGE_REASON
  }

  @Test
  fun aGridAlphaPastTheCeilingCarriesTheGridsReason() {
    val large = Size(16384, 16384)
    val alpha = AlphaPlanes.avif()
    val planes = List(4) { HeifAlphaPlane(alpha.itemType, alpha.configuration, alpha.codestream, large) }
    val bytes = grid(large, alpha = planes)
    val read = heifAlpha(bytes).shouldNotBeNull()
    read.refusal shouldBe GRID_TOO_LARGE_REASON
    read.grid.shouldBeNull()
  }

  private fun grid(
    tileSize: Size,
    columns: Int = 2,
    rows: Int = 2,
    size: Size = Size(tileSize.width * columns, tileSize.height * rows),
    alpha: List<HeifAlphaPlane>? = null,
  ): ByteArray =
    HeifStills.grid(
      "avif",
      tile.itemType,
      tile.configuration,
      tile.codestream,
      tileSize,
      columns,
      rows,
      size,
      alpha = alpha,
    )

  private fun read(bytes: ByteArray): HeifGrid = heifPrimaryGrid(bytes).shouldBeInstanceOf<HeifGridRead.Read>().grid
}
