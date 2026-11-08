package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.heif.ASSEMBLY_TEXEL_BYTES
import dev.jordond.filmstrip.picture.heif.HeifOverlay
import dev.jordond.filmstrip.picture.heif.HeifOverlayRead
import dev.jordond.filmstrip.picture.heif.OVERLAY_INPUT_OUT_OF_RANGE_REASON
import dev.jordond.filmstrip.picture.heif.OVERLAY_NESTED_INPUT_REASON
import dev.jordond.filmstrip.picture.heif.OVERLAY_TOO_LARGE_REASON
import dev.jordond.filmstrip.picture.heif.heifPrimaryGrid
import dev.jordond.filmstrip.picture.heif.heifPrimaryOverlay
import dev.jordond.filmstrip.picture.heifPrimaryIsTiled
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * HEIF stills whose primary item is an `iovl`, read back through core's own reader.
 */
class HeifOverlayTest {
  private val input = ColorPictures.NCLX_BT709_LIMITED.thumbnail()

  @Test
  fun anOverlaysInputsCarryTheirOffsetsAndSizesInOrder() {
    for (wide in listOf(false, true)) {
      val offsets = listOf(0 to 0, 16 to 8, -3 to 4)
      val fill = listOf(65535, 32896, 0, 40000)
      val bytes = HeifOverlays.overlay("avif", input, offsets, Size(24, 16), fill, wide = wide)
      val overlay = withClue("wide $wide") { read(bytes) }
      overlay.inputs.map { it.item } shouldBe listOf(2L, 3L, 4L)
      overlay.inputs.map { it.x to it.y } shouldBe offsets
      overlay.inputs.map { it.size }.distinct() shouldBe listOf(Size(8, 8))
      overlay.size shouldBe Size(24, 16)
      listOf(overlay.fill.red, overlay.fill.green, overlay.fill.blue, overlay.fill.alpha) shouldBe fill
      overlay.fill.opaque.shouldBeFalse()
      heifPrimaryIsTiled(bytes).shouldBeTrue()
      heifPrimaryGrid(bytes).shouldBeNull()
    }
  }

  @Test
  fun inputsOfDifferentImagesCarryTheirOwnSizes() {
    val wider = HeifThumbnail(input.itemType, input.configuration, input.codestream, Size(16, 4))
    val bytes = HeifOverlays.overlay("avif", input, listOf(0 to 0, 2 to 9), Size(20, 14), images = listOf(input, wider))
    read(bytes).inputs.map { it.size } shouldBe listOf(Size(8, 8), Size(16, 4))
  }

  @Test
  fun thePeakCountsTheCanvasAndEveryInputAtItsWholeSize() {
    val wider = HeifThumbnail(input.itemType, input.configuration, input.codestream, Size(16, 4))
    val images = listOf(input, wider, input)
    val overlay =
      read(HeifOverlays.overlay("avif", input, listOf(-3 to 4, 20 to -2, 2 to 2), Size(24, 16), images = images))
    overlay.peakBytes shouldBe (24L * 16 + 8 * 8 + 16 * 4 + 8 * 8) * ASSEMBLY_TEXEL_BYTES
  }

  @Test
  fun eachInputShowsOnlyThePartThatLandsOnTheCanvas() {
    val offsets = listOf(-3 to 4, 20 to -2, 2 to 2, 30 to 0)
    val overlay = read(HeifOverlays.overlay("avif", input, offsets, Size(24, 16)))
    val shown = overlay.shown
    shown.map { it.input.item } shouldBe listOf(2L, 3L, 4L)
    shown.map { listOf(it.x, it.y, it.size.width, it.size.height, it.left, it.top) } shouldBe
      listOf(listOf(0, 4, 5, 8, 3, 0), listOf(20, 0, 4, 6, 0, 2), listOf(2, 2, 8, 8, 0, 0))
  }

  @Test
  fun theFillShowsWhereverNoInputCoversTheCanvas() {
    val shown = { offsets: List<Pair<Int, Int>>, canvas: Size ->
      read(HeifOverlays.overlay("avif", input, offsets, canvas)).fillShown
    }
    withClue("two inputs side by side") { shown(listOf(0 to 0, 8 to 0), Size(16, 8)).shouldBeFalse() }
    withClue("two inputs overlapping across the canvas") { shown(listOf(-2 to 0, 6 to 0), Size(14, 8)).shouldBeFalse() }
    withClue("a gap in the middle column") { shown(listOf(0 to 0, 9 to 0), Size(17, 8)).shouldBeTrue() }
    withClue("a row below the inputs") { shown(listOf(0 to 0, 8 to 0), Size(16, 9)).shouldBeTrue() }
    withClue("a hole in the middle of four") {
      shown(listOf(0 to 0, 10 to 0, 0 to 10, 10 to 10, 5 to 0, 0 to 5, 10 to 5, 5 to 10), Size(18, 18)).shouldBeTrue()
    }
    withClue("the hole filled") {
      shown(listOf(0 to 0, 10 to 0, 0 to 10, 10 to 10, 5 to 0, 0 to 5, 10 to 5, 5 to 10, 5 to 5), Size(18, 18))
        .shouldBeFalse()
    }
  }

  @Test
  fun anOverlayOfHundredsOfInputsReadsEveryOne() {
    val offsets = List(MANY_INPUTS) { (it % MOSAIC_COLUMNS) * 8 to (it / MOSAIC_COLUMNS) * 8 }
    val canvas = Size(MOSAIC_COLUMNS * 8, MANY_INPUTS / MOSAIC_COLUMNS * 8)
    val bytes = HeifOverlays.overlay("avif", input, offsets, canvas)
    read(bytes).inputs.map { it.x to it.y } shouldBe offsets
  }

  @Test
  fun aMosaicOfThousandsOfInputsShowsItsFillOnlyThroughAGap() {
    val cells = List(BIG_MOSAIC_SIDE * BIG_MOSAIC_SIDE) { it }
    val at = { cell: Int -> (cell % BIG_MOSAIC_SIDE) * 8 to (cell / BIG_MOSAIC_SIDE) * 8 }
    val canvas = Size(BIG_MOSAIC_SIDE * 8, BIG_MOSAIC_SIDE * 8)
    val whole = read(HeifOverlays.overlay("avif", input, cells.map(at), canvas))
    withClue("every input sized") { whole.inputs.map { it.size }.distinct() shouldBe listOf(Size(8, 8)) }
    withClue("every cell covered") { whole.fillShown.shouldBeFalse() }
    val gap = cells.size / 2 + BIG_MOSAIC_SIDE / 3
    val gapped = read(HeifOverlays.overlay("avif", input, (cells - gap).map(at), canvas))
    withClue("one cell left out") { gapped.fillShown.shouldBeTrue() }
  }

  @Test
  fun anInputThatIsItselfAGridIsRefused() {
    val bytes = HeifOverlays.overlay("avif", input, listOf(0 to 0, 8 to 0), Size(16, 8), nested = setOf(1))
    refusal(bytes) shouldBe OVERLAY_NESTED_INPUT_REASON
  }

  @Test
  fun anInputWhoseEdgePassesIntIsRefusedWithoutWrapping() {
    val bytes = HeifOverlays.overlay("avif", input, listOf(0 to 0, Int.MAX_VALUE - 4 to 0), Size(8, 8), wide = true)
    refusal(bytes) shouldBe OVERLAY_INPUT_OUT_OF_RANGE_REASON
  }

  @Test
  fun anInputFarOffASmallCanvasReadsAndShowsNothing() {
    val overlay = read(HeifOverlays.overlay("avif", input, listOf(30000 to 30000), Size(8, 8)))
    overlay.shown shouldBe emptyList()
    overlay.fillShown.shouldBeTrue()
    overlay.peakBytes shouldBe (8L * 8 + 8 * 8) * ASSEMBLY_TEXEL_BYTES
  }

  @Test
  fun aThousandLargeInputsOverOneCanvasAreRefused() {
    val large = HeifThumbnail(input.itemType, input.configuration, input.codestream, Size(4096, 4096))
    val bytes =
      HeifOverlays.overlay("avif", input, List(1000) { 0 to 0 }, Size(4096, 4096), images = List(1000) { large })
    refusal(bytes) shouldBe OVERLAY_TOO_LARGE_REASON
  }

  @Test
  fun aPeakAtTheCeilingReadsAndOneRowPastIsRefused() {
    val width = 16384
    val strip = HeifThumbnail(input.itemType, input.configuration, input.codestream, Size(width, 8))
    // The canvas and an input eight rows high and as wide, held together.
    val rows = (PICTURE_PEAK_CEILING / (ASSEMBLY_TEXEL_BYTES * width) - 8).toInt()
    val at = { canvas: Int ->
      HeifOverlays.overlay("avif", input, listOf(0 to canvas / 2), Size(width, canvas), images = listOf(strip))
    }
    read(at(rows)).peakBytes shouldBe PICTURE_PEAK_CEILING
    refusal(at(rows + 1)) shouldBe OVERLAY_TOO_LARGE_REASON
  }

  @Test
  fun aPictureWhosePrimaryItemIsNoOverlayHasNone() {
    val avif = ColorPictures.NCLX_BT709_LIMITED
    heifPrimaryOverlay(avif.bytes()).shouldBeNull()
    heifPrimaryOverlay(avif.gridBytes()).shouldBeNull()
    val truncated = HeifOverlays.overlay("avif", input, listOf(0 to 0), Size(8, 8)).copyOf(TRUNCATED)
    withClue("a truncated overlay") { heifPrimaryOverlay(truncated).shouldBeNull() }
    val empty = HeifOverlays.overlay("avif", input, emptyList(), Size(8, 8))
    withClue("an overlay with no inputs") { heifPrimaryOverlay(empty).shouldBeNull() }
    withClue("an overlay with no inputs is still tiled") { heifPrimaryIsTiled(empty).shouldBeTrue() }
    withClue("a PNG") { heifPrimaryOverlay(PNG_SIGNATURE).shouldBeNull() }
  }

  private fun read(bytes: ByteArray): HeifOverlay =
    heifPrimaryOverlay(bytes).shouldBeInstanceOf<HeifOverlayRead.Read>().overlay

  private fun refusal(bytes: ByteArray): String =
    heifPrimaryOverlay(bytes).shouldBeInstanceOf<HeifOverlayRead.Refused>().reason

  private companion object {
    const val TRUNCATED = 40
    const val MANY_INPUTS = 300
    const val MOSAIC_COLUMNS = 20
    const val BIG_MOSAIC_SIDE = 128
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  }
}
