package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a [Camcorder] shows at a composition time.
 */
class OsdStateTest {
  @Test
  fun `a twelve hour clock reads noon as PM 12 and midnight as AM 12`() {
    clockText(RecordedAt(1997, 6, 14, 12, 0, 0), ClockStyle.TwelveHour) shouldBe "PM 12:00:00"
    clockText(RecordedAt(1997, 6, 14, 0, 5, 9), ClockStyle.TwelveHour) shouldBe "AM 12:05:09"
    clockText(RecordedAt(1997, 6, 14, 15, 32, 5), ClockStyle.TwelveHour) shouldBe "PM 3:32:05"
    clockText(RecordedAt(1997, 6, 14, 15, 32, 5), ClockStyle.TwentyFourHour) shouldBe "15:32:05"
  }

  @Test
  fun `the clock reads the recorded time plus the composition time`() {
    val spec = Camcorder(listOf(OsdElement.Clock(ClockStyle.TwentyFourHour)), RecordedAt(1997, 6, 14, 23, 59, 58))

    lineText(spec, 1.5.seconds) shouldBe "23:59:59"
    lineText(spec, 2.5.seconds) shouldBe "00:00:00"
  }

  @Test
  fun `a date reads in each style`() {
    val at = RecordedAt(1997, 6, 4)

    dateText(at, DateStyle.MonthDayYear) shouldBe "JUN. 4 1997"
    dateText(at, DateStyle.DayMonthYear) shouldBe " 4.JUN 1997"
    dateText(at, DateStyle.YearMonthDay) shouldBe "1997.06.04"
  }

  @Test
  fun `a counter with frames at 29_97 reads a mid time`() {
    val counter = OsdElement.Counter(start = 1.minutes, withFrames = true)
    val time = (83.seconds + 500.milliseconds).inWholeMicroseconds

    counterText(counter, time, NTSC) shouldBe "0:02:23:14"
    counterText(OsdElement.Counter(), time, NTSC) shouldBe "0:01:23"
  }

  @Test
  fun `a frame count at 120 fps pads to three digits so a mid second line keeps its width`() {
    val counter = OsdElement.Counter(withFrames = true)
    val spec = Camcorder(listOf(counter))
    val early = CamcorderOsd(spec, HIGH_RATE.toFloat()).stateAt(40.milliseconds).lines.single()
    val middle = CamcorderOsd(spec, HIGH_RATE.toFloat()).stateAt(900.milliseconds).lines.single()

    counterText(counter, 500.milliseconds.inWholeMicroseconds, HIGH_RATE) shouldBe "0:00:00:060"
    lineDots(middle) shouldBe lineDots(early)
    frameDigits(HIGH_RATE) shouldBe 3
    frameDigits(NTSC) shouldBe 2
  }

  @Test
  fun `a frame timestamp rounded to the microsecond counts as its own frame`() {
    val frame = 17L
    val micros = 5_000_000L + (frame * 1_000_000 / NTSC).toLong()

    frameWithinSecond(micros, NTSC) shouldBe frame
  }

  @Test
  fun `the blink is on at a quarter second and off at three quarters`() {
    blinkOn(250.milliseconds) shouldBe true
    blinkOn(750.milliseconds) shouldBe false
    blinkOn(1.25.seconds) shouldBe true
  }

  @Test
  fun `the record dot hides on the off half of a blink but keeps its place`() {
    val spec = Camcorder(listOf(OsdElement.Rec()))
    val on = osd(spec).stateAt(250.milliseconds).lines.single()
    val off = osd(spec).stateAt(750.milliseconds).lines.single()

    on.runs.first().colour shouldBe OsdParams.RECORD_COLOUR
    off.runs.first().colour shouldBe HIDDEN
    lineDots(off) shouldBe lineDots(on)
  }

  @Test
  fun `a battery shows its level in thirds and blinks only when low`() {
    batteryBars(0f) shouldBe 0
    batteryBars(0.25f) shouldBe 1
    batteryBars(0.5f) shouldBe 2
    batteryBars(1f) shouldBe 3

    val low = Camcorder(listOf(OsdElement.Battery(0.25f)))
    val full = Camcorder(listOf(OsdElement.Battery(0.9f)))
    osd(low)
      .stateAt(750.milliseconds)
      .lines
      .single()
      .runs
      .single()
      .colour shouldBe HIDDEN
    osd(low)
      .stateAt(250.milliseconds)
      .lines
      .single()
      .runs
      .single()
      .colour shouldBe OsdParams.TEXT_COLOUR
    osd(full)
      .stateAt(750.milliseconds)
      .lines
      .single()
      .runs
      .single()
      .colour shouldBe OsdParams.TEXT_COLOUR
  }

  @Test
  fun `the state holds within half a second and changes across a second`() {
    val spec = Camcorder(listOf(OsdElement.Counter(), OsdElement.Tape(TapeSpeed.SP)))

    osd(spec).stateAt(1.1.seconds) shouldBe osd(spec).stateAt(1.4.seconds)
    osd(spec).stateAt(1.9.seconds) shouldNotBe osd(spec).stateAt(2.1.seconds)
  }

  @Test
  fun `a label with a character outside the font fails by name`() {
    val error = shouldThrow<IllegalArgumentException> { OsdElement.Label("HELLO?") }

    error.message shouldContain "'?'"
    shouldThrow<IllegalArgumentException> { OsdElement.Label("hello") }.message shouldContain "'h'"
  }

  private fun osd(spec: Camcorder) = CamcorderOsd(spec, NTSC.toFloat())

  private fun lineText(
    spec: Camcorder,
    time: Duration,
  ): String =
    osd(spec)
      .stateAt(time)
      .lines
      .single()
      .runs
      .joinToString("") { it.text }

  private companion object {
    const val NTSC = 30_000.0 / 1_001.0
    const val HIGH_RATE = 120.0
  }
}
