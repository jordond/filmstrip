package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.SpriteSheets
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SpriteSheetTest {
  private val frame = 1.seconds / 30

  @Test
  fun theOneDurationFormEqualsTheListHoldingThatDurationForEveryFrame() {
    val short = SpriteSheet(4, 3, frame)
    val long = SpriteSheet(4, 3, List(12) { frame })

    short shouldBe long
    short.hashCode() shouldBe long.hashCode()
    short.frameCount shouldBe 12
  }

  @Test
  fun theOneDurationFormTakesAFrameCountBelowTheCellCount() {
    SpriteSheet(4, 3, frame, frameCount = 10) shouldBe SpriteSheet(4, 3, List(10) { frame })
  }

  @Test
  fun sheetsWithEqualUnevenDurationsAreEqualAndHashEqual() {
    val one = SpriteSheet(4, 3, SpriteSheets.UNEVEN, FramePlays.Times(2))
    val other = SpriteSheet(4, 3, SpriteSheets.UNEVEN.toList(), FramePlays.Times(2))

    one shouldBe other
    one.hashCode() shouldBe other.hashCode()
  }

  @Test
  fun sheetsDifferWhenAnyOfTheirFieldsDo() {
    val base = SpriteSheet(4, 3, SpriteSheets.UNEVEN)

    (base == SpriteSheet(4, 3, SpriteSheets.UNEVEN, FramePlays.Once)) shouldBe false
    (base == SpriteSheet(3, 4, SpriteSheets.UNEVEN)) shouldBe false
    (base == SpriteSheet(4, 3, SpriteSheets.UNEVEN.reversed())) shouldBe false
    (base == SpriteSheet(4, 3, SpriteSheets.UNEVEN.take(11))) shouldBe false
  }

  @Test
  fun overlaysBuiltFromEqualSheetsAreEqualAndHashEqual() {
    val image = ImageSource.Path("/fixtures/sheet.png")

    listOf(SpriteSheets.UNEVEN, List(12) { frame }).forEach { durations ->
      val one = ImageOverlay(image, Corner.TopEnd, sheet = SpriteSheet(4, 3, durations))
      val other = ImageOverlay(image, Corner.TopEnd, sheet = SpriteSheet(4, 3, durations.toList()))

      one shouldBe other
      one.hashCode() shouldBe other.hashCode()
    }
  }

  @Test
  fun changingTheCallersListAfterwardsChangesNothing() {
    val durations = SpriteSheets.UNEVEN.toMutableList()
    val sheet = SpriteSheet(4, 3, durations)
    val hash = sheet.hashCode()

    durations[3] = 5.milliseconds
    durations.removeAt(0)

    sheet shouldBe SpriteSheet(4, 3, SpriteSheets.UNEVEN)
    sheet.hashCode() shouldBe hash
    sheet.frameDurations shouldBe SpriteSheets.UNEVEN
  }

  @Test
  fun aShortDurationIsKeptAsWritten() {
    val sheet = SpriteSheet(2, 2, listOf(5.milliseconds, 10.milliseconds))

    sheet.frameDurations shouldBe listOf(5.milliseconds, 10.milliseconds)
    SpriteSheet(2, 2, 5.milliseconds).frameDurations shouldBe List(4) { 5.milliseconds }
  }

  @Test
  fun anEmptyListIsRefused() {
    assertFailsWith<IllegalArgumentException> { SpriteSheet(4, 3, emptyList()) }
      .message
      .shouldContain("empty")
  }

  @Test
  fun moreDurationsThanCellsIsRefusedByNamingTheCounts() {
    val message = assertFailsWith<IllegalArgumentException> { SpriteSheet(4, 3, List(13) { frame }) }.message

    message.shouldContain("4 by 3")
    message.shouldContain("13")
  }

  @Test
  fun aZeroNegativeOrInfiniteDurationInTheMiddleIsRefusedByNamingItsIndex() {
    listOf(Duration.ZERO, (-1).milliseconds, Duration.INFINITE).forEach { bad ->
      val durations = List(12) { if (it == 6) bad else frame }

      assertFailsWith<IllegalArgumentException> { SpriteSheet(4, 3, durations) }
        .message
        .shouldContain("Frame 6")
    }
  }

  @Test
  fun aBadDurationOnTheOneDurationFormIsRefused() {
    listOf(Duration.ZERO, (-1).milliseconds, Duration.INFINITE).forEach { bad ->
      assertFailsWith<IllegalArgumentException> { SpriteSheet(4, 3, bad) }
    }
  }

  @Test
  fun aFrameCountOutsideTheGridIsRefusedByNamingIt() {
    listOf(0, 13).forEach { count ->
      assertFailsWith<IllegalArgumentException> { SpriteSheet(4, 3, frame, frameCount = count) }
        .message
        .shouldContain("$count")
    }
  }

  @Test
  fun aGridWithNoColumnsOrRowsIsRefused() {
    listOf(0 to 3, 4 to 0, -1 to 3).forEach { (columns, rows) ->
      assertFailsWith<IllegalArgumentException> { SpriteSheet(columns, rows, listOf(frame)) }
      assertFailsWith<IllegalArgumentException> { SpriteSheet(columns, rows, frame) }
    }
  }

  @Test
  fun aPlayCountBelowOneIsRefused() {
    assertFailsWith<IllegalArgumentException> { SpriteSheet(1, 1, frame, plays = FramePlays.Times(0)) }
  }
}
