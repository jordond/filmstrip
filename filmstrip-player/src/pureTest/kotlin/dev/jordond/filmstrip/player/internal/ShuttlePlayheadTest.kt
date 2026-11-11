package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.ShuttleEdge
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ShuttlePlayheadTest {
  private val composition = 0.seconds..60.seconds

  // Mid-span on purpose. At the ends an additive reading of the rate agrees with the multiplicative one.
  @Test
  fun `the playhead moves by the rate times the elapsed time`() {
    val cases =
      listOf(
        3f to 39.seconds,
        -3f to 21.seconds,
        7f to 51.seconds,
        -7f to 9.seconds,
        0.5f to 31500.milliseconds,
      )
    for ((rate, expected) in cases) {
      val playhead = shuttlePlayheadAt(origin = 30.seconds, rate = rate, elapsed = 3.seconds, bounds = composition)

      playhead.position shouldBe expected
      playhead.edge shouldBe null
    }
  }

  @Test
  fun `no elapsed time leaves the playhead on its origin`() {
    val playhead = shuttlePlayheadAt(origin = 30.seconds, rate = 7f, elapsed = 0.seconds, bounds = composition)

    playhead.position shouldBe 30.seconds
    playhead.edge shouldBe null
  }

  @Test
  fun `a forward shuttle holds at the end and reports it`() {
    val arriving = shuttlePlayheadAt(origin = 30.seconds, rate = 3f, elapsed = 10.seconds, bounds = composition)
    val past = shuttlePlayheadAt(origin = 30.seconds, rate = 3f, elapsed = 25.seconds, bounds = composition)

    arriving.position shouldBe 60.seconds
    arriving.edge shouldBe ShuttleEdge.End
    past.position shouldBe 60.seconds
    past.edge shouldBe ShuttleEdge.End
  }

  @Test
  fun `a reverse shuttle holds at the start and reports it`() {
    val arriving = shuttlePlayheadAt(origin = 30.seconds, rate = -3f, elapsed = 10.seconds, bounds = composition)
    val past = shuttlePlayheadAt(origin = 30.seconds, rate = -7f, elapsed = 25.seconds, bounds = composition)

    arriving.position shouldBe 0.seconds
    arriving.edge shouldBe ShuttleEdge.Start
    past.position shouldBe 0.seconds
    past.edge shouldBe ShuttleEdge.Start
  }

  @Test
  fun `an edge is only reported in the direction of travel`() {
    val leavingStart = shuttlePlayheadAt(origin = 0.seconds, rate = 3f, elapsed = 0.seconds, bounds = composition)
    val intoStart = shuttlePlayheadAt(origin = 0.seconds, rate = -3f, elapsed = 0.seconds, bounds = composition)
    val leavingEnd = shuttlePlayheadAt(origin = 60.seconds, rate = -3f, elapsed = 0.seconds, bounds = composition)
    val intoEnd = shuttlePlayheadAt(origin = 60.seconds, rate = 3f, elapsed = 0.seconds, bounds = composition)

    leavingStart.edge shouldBe null
    intoStart.edge shouldBe ShuttleEdge.Start
    leavingEnd.edge shouldBe null
    intoEnd.edge shouldBe ShuttleEdge.End
  }

  @Test
  fun `a playback range is the bounds`() {
    val range = 10.seconds..20.seconds

    val inside = shuttlePlayheadAt(origin = 15.seconds, rate = 3f, elapsed = 1.seconds, bounds = range)
    val end = shuttlePlayheadAt(origin = 15.seconds, rate = 7f, elapsed = 1.seconds, bounds = range)
    val start = shuttlePlayheadAt(origin = 15.seconds, rate = -7f, elapsed = 1.seconds, bounds = range)

    inside.position shouldBe 18.seconds
    inside.edge shouldBe null
    end.position shouldBe 20.seconds
    end.edge shouldBe ShuttleEdge.End
    start.position shouldBe 10.seconds
    start.edge shouldBe ShuttleEdge.Start
  }

  @Test
  fun `an origin outside the bounds is pulled in`() {
    val before = shuttlePlayheadAt(origin = 2.seconds, rate = 3f, elapsed = 0.seconds, bounds = 10.seconds..20.seconds)
    val after = shuttlePlayheadAt(origin = 40.seconds, rate = -3f, elapsed = 0.seconds, bounds = 10.seconds..20.seconds)

    before.position shouldBe 10.seconds
    before.edge shouldBe null
    after.position shouldBe 20.seconds
    after.edge shouldBe null
  }

  @Test
  fun `the bounds are the first and last frames of the composition`() {
    val bounds = shuttleBoundsOf(10.seconds, frameRate = 30, playbackRange = null)

    bounds.start shouldBe 0.seconds
    bounds.endInclusive shouldBe outputSlotTime(299, 30)
  }

  @Test
  fun `the bounds are the first and last frames a playback range plays`() {
    val inside = shuttleBoundsOf(10.seconds, 30, TimeRange.of(2.seconds, 4.seconds))
    val open = shuttleBoundsOf(10.seconds, 30, TimeRange.from(7.seconds))
    val past = shuttleBoundsOf(10.seconds, 30, TimeRange.of(8.seconds, 25.seconds))

    inside shouldBe outputSlotTime(60, 30)..outputSlotTime(119, 30)
    open shouldBe outputSlotTime(210, 30)..outputSlotTime(299, 30)
    past shouldBe outputSlotTime(240, 30)..outputSlotTime(299, 30)
  }

  @Test
  fun `a playback range starting past the end leaves the whole composition`() {
    val bounds = shuttleBoundsOf(10.seconds, 30, TimeRange.from(12.seconds))

    bounds shouldBe 0.seconds..outputSlotTime(299, 30)
  }

  @Test
  fun `the rates at the limit are accepted`() {
    for (rate in listOf(32f, -32f, 0.01f, -0.01f, 3f, -7f)) requireShuttleRate(rate)
  }

  @Test
  fun `a zero or non-finite or oversized rate is refused`() {
    val refused = listOf(0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 32.5f, -33f)
    for (rate in refused) {
      assertFailsWith<IllegalArgumentException> { requireShuttleRate(rate) }
    }
  }
}
