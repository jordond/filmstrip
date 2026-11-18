package dev.jordond.filmstrip.test

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SpriteSheetsTest {
  private fun cutFrom(
    sheet: IntArray,
    index: Int,
  ): IntArray {
    val cell = SpriteSheets.CELL
    val left = (index % SpriteSheets.COLUMNS) * cell.width
    val top = (index / SpriteSheets.COLUMNS) * cell.height
    val stride = SpriteSheets.SHEET.width
    return IntArray(cell.width * cell.height) { sheet[(top + it / cell.width) * stride + left + it % cell.width] }
  }

  @Test
  fun everyCellReadsBackItsOwnIndexWithAnIntactRing() {
    val sheet = SpriteSheets.pixels()

    (0 until 12).map { SpriteSheets.indexOf(cutFrom(sheet, it)) } shouldBe (0 until 12).toList()
    (0 until 12).map { SpriteSheets.bledTexels(cutFrom(sheet, it)) } shouldBe List(12) { 0 }
  }

  @Test
  fun aTexelFromTheNextCellOnTheRingCountsAsBled() {
    val cell = SpriteSheets.cellPixels(3)
    cell[0] = SpriteSheets.colorOf(4)

    SpriteSheets.bledTexels(cell) shouldBe 1
  }

  @Test
  fun noCellInteriorHoldsTheRingColor() {
    (0 until 12).map { SpriteSheets.colorOf(it) }.contains(SpriteSheets.RING) shouldBe false
  }

  @Test
  fun aSheetOfTenFramesLeavesItsLastTwoCellsTransparent() {
    val sheet = SpriteSheets.pixels(10)

    (0 until 10).map { SpriteSheets.indexOf(cutFrom(sheet, it)) } shouldBe (0 until 10).toList()
    cutFrom(sheet, 10).all { it == 0 } shouldBe true
    cutFrom(sheet, 11).all { it == 0 } shouldBe true
  }

  @Test
  fun theUnevenDurationsRepeatTheirPatternAcrossEveryCell() {
    SpriteSheets.UNEVEN.size shouldBe 12
    SpriteSheets.UNEVEN[5] shouldBe SpriteSheets.UNEVEN[0]
    SpriteSheets.UNEVEN[11] shouldBe SpriteSheets.UNEVEN[1]
  }

  @Test
  fun everyPngVariantIsWrittenAsAPngOfTheSizeItClaims() {
    fun widthOf(png: ByteArray): Int =
      png.copyOfRange(16, 20).fold(0) { width, byte -> (width shl 8) or (byte.toInt() and 0xFF) }

    widthOf(SpriteSheets.png()) shouldBe SpriteSheets.SHEET.width
    widthOf(SpriteSheets.png(10)) shouldBe SpriteSheets.SHEET.width
    widthOf(SpriteSheets.sixteenBitPng()) shouldBe SpriteSheets.SHEET.width
    widthOf(SpriteSheets.flatPng()) shouldBe SpriteSheets.SHEET.width
    widthOf(SpriteSheets.undivisiblePng()) shouldBe SpriteSheets.UNDIVISIBLE.width
    widthOf(SpriteSheets.orientedPng()) shouldBe SpriteSheets.SHEET.height
  }

  @Test
  fun everyFlatCellIsItsOwnColorToItsEdges() {
    val sheet = SpriteSheets.flatPixels()

    (0 until 12).forEach { index ->
      cutFrom(sheet, index).all { it == SpriteSheets.colorOf(index) } shouldBe true
    }
  }

  @Test
  fun aFlatCellAtAnyCoverageHoldsNoForeignTexel() {
    val own = SpriteSheets.colorOf(5)
    val frame =
      intArrayOf(0, own, halfOf(own), halfOf(halfOf(own)))

    SpriteSheets.foreignTexels(frame, 5) shouldBe 0
  }

  @Test
  fun aTexelOfTheNextCellCountsAsForeignInTheMiddleOfACoverageRange() {
    val own = SpriteSheets.colorOf(5)
    val mixed = mix(halfOf(own), halfOf(SpriteSheets.colorOf(6)))

    SpriteSheets.foreignTexels(intArrayOf(own, mixed), 5) shouldBe 1
  }

  private fun halfOf(color: Int): Int =
    (0xFF shl 24) or (((color shr 16 and 0xFF) / 2) shl 16) or (((color shr 8 and 0xFF) / 2) shl 8) or
      ((color and 0xFF) / 2)

  private fun mix(
    a: Int,
    b: Int,
  ): Int =
    (0xFF shl 24) or (((a shr 16 and 0xFF) + (b shr 16 and 0xFF)) / 2 shl 16) or
      (((a shr 8 and 0xFF) + (b shr 8 and 0xFF)) / 2 shl 8) or ((a and 0xFF) + (b and 0xFF)) / 2
}
