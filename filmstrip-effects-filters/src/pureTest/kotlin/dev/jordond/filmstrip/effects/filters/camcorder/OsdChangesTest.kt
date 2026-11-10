package dev.jordond.filmstrip.effects.filters.camcorder

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The states a [Camcorder] display runs through, which the ffmpeg route lays one frame each.
 */
class OsdChangesTest {
  @Test
  fun `every change lands on the state clock where the display first differs`() {
    val osd = CamcorderOsd(BUSY, NTSC)
    val changes = osd.changesWithin(200.milliseconds, 3.seconds)
    val tick = OsdParams.STATE_CLOCK_MICROS.microseconds

    changes.first().at shouldBe 200.milliseconds
    changes.zipWithNext().forEach { (before, after) ->
      after.at.inWholeMicroseconds % OsdParams.STATE_CLOCK_MICROS shouldBe 0
      after.state shouldNotBe before.state
      osd.stateAt(after.at) shouldBe after.state
      osd.stateAt(after.at - tick) shouldBe before.state
      osd.stateAt((before.at + after.at) / 2) shouldBe before.state
    }
  }

  @Test
  fun `a counter with frames changes on every frame`() {
    val counter = Camcorder(listOf(OsdElement.Counter(withFrames = true)))
    val changes = CamcorderOsd(counter, NTSC).changesWithin(ZERO, 1.seconds)

    changes.size shouldBe 30
  }

  @Test
  fun `the state holds still between ticks of the state clock`() {
    val osd = CamcorderOsd(Camcorder(listOf(OsdElement.Rec())), NTSC)

    osd.stateAt(500.milliseconds - 1.microseconds) shouldBe osd.stateAt(480.milliseconds)
    osd.stateAt(500.milliseconds) shouldNotBe osd.stateAt(499_980.microseconds)
  }

  @Test
  fun `an hour of a blinking clock is one state each half second`() {
    val clock = Camcorder(listOf(OsdElement.Rec(), OsdElement.Clock()))

    CamcorderOsd(clock, NTSC).changesWithin(ZERO, 1.hours).size shouldBe 7_200
  }

  @Test
  fun `a display that never changes is one state`() {
    val label = Camcorder(listOf(OsdElement.Label("HOME VIDEO")))

    CamcorderOsd(label, NTSC).changesWithin(ZERO, 1.hours).size shouldBe 1
  }

  private companion object {
    const val NTSC = 30_000f / 1_001f
    val ZERO = kotlin.time.Duration.ZERO
    val BUSY =
      Camcorder(
        listOf(
          OsdElement.Rec(),
          OsdElement.Counter(start = 900.milliseconds, withFrames = true),
          OsdElement.Clock(),
          OsdElement.Battery(0.2f),
        ),
      )
  }
}
