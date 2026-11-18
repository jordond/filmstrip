package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.test.SpriteSheets
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FlipbookTest {
  private val shown = SpriteSheets.SHEET
  private val frame = 1.seconds / 30

  @Test
  fun everyCellIsTheShownPictureDividedByTheGrid() {
    val read = SpriteSheet(4, 3, frame).flipbookOver(shown).shouldBeInstanceOf<FlipbookRead.Drawn>()

    read.flipbook.cell shouldBe Size(shown.width / 4, shown.height / 3)
    read.flipbook.cells[5] shouldBe PictureCrop(1 * 32, 1 * 16, Size(32, 16))
  }

  @Test
  fun aSheetOfTenFramesStopsAtTheLastFrameAndKeepsTheGrid() {
    val read = SpriteSheet(4, 3, frame, frameCount = 10).flipbookOver(shown).shouldBeInstanceOf<FlipbookRead.Drawn>()

    read.flipbook.cells.size shouldBe 10
    read.flipbook.cells.last() shouldBe PictureCrop(1 * 32, 2 * 16, Size(32, 16))
  }

  @Test
  fun framesReadLeftToRightThenTopToBottom() {
    val read = SpriteSheet(4, 3, frame).flipbookOver(shown).shouldBeInstanceOf<FlipbookRead.Drawn>()

    read.flipbook.cells.map { it.left to it.top } shouldBe
      (0 until 12).map { (it % 4) * 32 to (it / 4) * 16 }
  }

  @Test
  fun aSideTheGridDoesNotDivideIsRefusedByName() {
    val sheet = SpriteSheet(4, 3, frame)

    listOf(SpriteSheets.UNDIVISIBLE, Size(128, 47), Size(0, 48)).forEach { size ->
      sheet
        .flipbookOver(size)
        .shouldBeInstanceOf<FlipbookRead.Refused>()
        .message
        .shouldContain("${size.width}x${size.height}")
    }
  }

  @Test
  fun aSheetOfOneFrameIsAStillOfItsCell() {
    val read = SpriteSheet(4, 3, frame, frameCount = 1).flipbookOver(shown).shouldBeInstanceOf<FlipbookRead.Still>()

    read.cell shouldBe PictureCrop(0, 0, Size(32, 16))
  }

  @Test
  fun aFlipbookShowsTheCellTheTimelineNamesInTheMiddleOfItsSecondCycle() {
    val durations = listOf(100.milliseconds, 30.milliseconds, 250.milliseconds, 40.milliseconds)
    val sheet = SpriteSheet(2, 2, durations)
    val flipbook = sheet.flipbookOver(Size(64, 32)).shouldBeInstanceOf<FlipbookRead.Drawn>().flipbook
    val drawnStart = 1.234.seconds

    for (rate in listOf(24, 30, 60)) {
      val first = outputSlotOf(drawnStart, rate)
      val cycleSlots = (flipbook.timeline.cycle.inWholeMicroseconds * rate / 1_000_000)
      for (slot in first + cycleSlots until first + 2 * cycleSlots) {
        val index = flipbook.timeline.frameOnSlot(drawnStart, slot, rate)

        flipbook.cellOnSlot(drawnStart, slot, rate) shouldBe flipbook.cells[index]
        flipbook.cellAt(drawnStart, outputSlotTime(slot, rate), rate) shouldBe flipbook.cells[index]
      }
    }
  }

  @Test
  fun theMiddleOfASecondCycleShowsEveryFrameInTurn() {
    val sheet = SpriteSheet(4, 3, SpriteSheets.UNEVEN, FramePlays.Forever)
    val flipbook = sheet.flipbookOver(shown).shouldBeInstanceOf<FlipbookRead.Drawn>().flipbook
    val rate = 60

    val shownCells =
      (0L until outputSlotOf(flipbook.timeline.cycle * 2, rate)).map {
        flipbook.cellOnSlot(Duration.ZERO, it, rate)
      }

    shownCells.distinct().size shouldBe 12
  }

  @Test
  fun aMissingFrameRateIsRefusedAndAResolvedOneIsNot() {
    attributes(frameRate = null).gridRate.shouldBeNull()
    flipbookRefusal(attributes(frameRate = null)) shouldBe FLIPBOOK_NO_GRID
    flipbookRefusal(attributes(frameRate = 0f)) shouldBe FLIPBOOK_NO_GRID
    flipbookRefusal(attributes(frameRate = 30f)).shouldBeNull()
    attributes(frameRate = 29.97f).gridRate shouldBe 30
  }

  private fun attributes(frameRate: Float?): Attributes =
    Attributes(
      inputSize = Size(1280, 720),
      outputSize = Size(1280, 720),
      layoutSize = Size(1280, 720),
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = frameRate,
      span = TimeRange.of(Duration.ZERO, 10.seconds),
    )
}
