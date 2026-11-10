package dev.jordond.filmstrip.effects.filters.camcorder

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.days

/**
 * The calendar a [RecordedAt] carries the composition time across.
 */
class RecordedAtTest {
  @Test
  fun `a second past the last of a month lands on the first of the next`() {
    RecordedAt(1997, 4, 30, 23, 59, 59).plusSeconds(1) shouldBe RecordedAt(1997, 5, 1)
  }

  @Test
  fun `a leap year holds February 29`() {
    RecordedAt(1996, 2, 28, 12).plusSeconds(DAY) shouldBe RecordedAt(1996, 2, 29, 12)
    RecordedAt(2000, 2, 28, 12).plusSeconds(DAY) shouldBe RecordedAt(2000, 2, 29, 12)
  }

  @Test
  fun `1900 has no February 29`() {
    RecordedAt(1900, 2, 28, 12).plusSeconds(DAY) shouldBe RecordedAt(1900, 3, 1, 12)
    shouldThrow<IllegalArgumentException> { RecordedAt(1900, 2, 29) }
  }

  @Test
  fun `the last second of a year rolls into the next`() {
    RecordedAt(1999, 12, 31, 23, 59, 59).plusSeconds(1) shouldBe RecordedAt(2000, 1, 1)
  }

  @Test
  fun `forty days and a few hours carry through two months`() {
    RecordedAt(1997, 6, 14, 15, 32).plusSeconds(40 * DAY + 10 * HOUR + 125) shouldBe
      RecordedAt(1997, 7, 25, 1, 34, 5)
  }

  @Test
  fun `the last representable second holds instead of rolling past 9999`() {
    val last = RecordedAt(9999, 12, 31, 23, 59, 59)
    val clock = Camcorder(listOf(OsdElement.Date(), OsdElement.Clock(ClockStyle.TwentyFourHour)), last)

    last.plusSeconds(1) shouldBe last
    RecordedAt(9999, 12, 31, 23, 59, 58).plusSeconds(DAY) shouldBe last
    CamcorderOsd(clock, 30f).stateAt(1.days).lines.map { line -> line.runs.joinToString("") { it.text } } shouldBe
      listOf("DEC.31 9999", "23:59:59")
  }

  @Test
  fun `refuses a day past the end of its month by name`() {
    val error = shouldThrow<IllegalArgumentException> { RecordedAt(1997, 4, 31) }

    error.message shouldBe "Month 4 of 1997 has days 1 to 30, not 31."
  }

  private companion object {
    const val HOUR = 3_600L
    const val DAY = 86_400L
  }
}
