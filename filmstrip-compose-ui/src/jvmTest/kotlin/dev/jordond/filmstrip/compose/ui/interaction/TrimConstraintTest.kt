package dev.jordond.filmstrip.compose.ui.interaction

import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import dev.jordond.filmstrip.edit.TimeRange
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TrimConstraintTest {
  @Test
  fun `a minimum stops the handle that moved and leaves the other one alone`() {
    val constraint = TrimConstraint.MinDuration(2.seconds)

    constraint.constrain(TimeRange(9.seconds, 10.seconds), TrimHandle.Start, DURATION) shouldBe
      TimeRange(8.seconds, 10.seconds)
    constraint.constrain(TimeRange(4.seconds, 5.seconds), TrimHandle.End, DURATION) shouldBe
      TimeRange(4.seconds, 6.seconds)
  }

  @Test
  fun `a range that already fits is passed through`() {
    val constraint = TrimConstraint.MinDuration(2.seconds)
    val range = TimeRange(6.seconds, 14.seconds)

    constraint.constrain(range, TrimHandle.Start, DURATION) shouldBe range
    constraint.constrain(range, TrimHandle.End, DURATION) shouldBe range
  }

  @Test
  fun `an open end is read as the end of the source`() {
    TrimConstraint.MinDuration(2.seconds).constrain(
      TimeRange.from(5.seconds),
      TrimHandle.Start,
      DURATION,
    ) shouldBe TimeRange(5.seconds, DURATION)
  }

  @Test
  fun `a maximum stops a handle dragged away from the other one`() {
    val constraint = TrimConstraint.MinMaxDuration(2.seconds, 6.seconds)

    constraint.constrain(TimeRange(1.seconds, 12.seconds), TrimHandle.Start, DURATION) shouldBe
      TimeRange(6.seconds, 12.seconds)
    constraint.constrain(TimeRange(4.seconds, 19.seconds), TrimHandle.End, DURATION) shouldBe
      TimeRange(4.seconds, 10.seconds)

    // Between the two limits rather than at either, where a rule that swapped them would still pass.
    constraint.constrain(TimeRange(4.seconds, 8.seconds), TrimHandle.End, DURATION) shouldBe
      TimeRange(4.seconds, 8.seconds)
  }

  @Test
  fun `a fixed length slides the whole window`() {
    val constraint = TrimConstraint.FixedDuration(5.seconds)

    constraint.constrain(TimeRange(3.seconds, 20.seconds), TrimHandle.Start, DURATION) shouldBe
      TimeRange(3.seconds, 8.seconds)
    constraint.constrain(TimeRange(0.seconds, 12.seconds), TrimHandle.End, DURATION) shouldBe
      TimeRange(7.seconds, 12.seconds)
  }

  @Test
  fun `a range is never returned shorter than the minimum`() {
    // Squeezed against the end of the source, where there is no room behind the moving handle for
    // the minimum unless the other end gives way.
    val constraint = TrimConstraint.MinDuration(2.seconds)

    constraint.constrain(TimeRange(19.seconds, DURATION), TrimHandle.End, DURATION) shouldBe
      TimeRange(18.seconds, DURATION)
    constraint.constrain(TimeRange(Duration.ZERO, 1.seconds), TrimHandle.Start, DURATION) shouldBe
      TimeRange(Duration.ZERO, 2.seconds)
  }

  @Test
  fun `no range runs past either end of the source`() {
    val constraint = TrimConstraint.MinDuration(2.seconds)

    constraint.constrain(TimeRange((-4).seconds, 10.seconds), TrimHandle.Start, DURATION) shouldBe
      TimeRange(Duration.ZERO, 10.seconds)
    constraint.constrain(TimeRange(4.seconds, 90.seconds), TrimHandle.End, DURATION) shouldBe
      TimeRange(4.seconds, DURATION)

    TrimConstraint.FixedDuration(5.seconds).constrain(
      TimeRange(18.seconds, 23.seconds),
      TrimHandle.Start,
      DURATION,
    ) shouldBe TimeRange(15.seconds, DURATION)
  }

  @Test
  fun `a limit longer than the source is cut down to it`() {
    TrimConstraint.FixedDuration(60.seconds).constrain(
      TimeRange(4.seconds, 9.seconds),
      TrimHandle.Start,
      DURATION,
    ) shouldBe TimeRange(Duration.ZERO, DURATION)

    // A minimum at least as long as the source leaves exactly one range that satisfies it, so the
    // handle that did not move is pulled along rather than a too-short range being returned.
    TrimConstraint.MinDuration(60.seconds).constrain(
      TimeRange(4.seconds, 9.seconds),
      TrimHandle.End,
      DURATION,
    ) shouldBe TimeRange(Duration.ZERO, DURATION)
  }

  @Test
  fun `a drag applies each delta to where the handle already is`() {
    val scale = TimelineScale(DURATION, 100f)
    val constraint = TrimConstraint.MinDuration(2.seconds)
    var range = TimeRange(Duration.ZERO, 10.seconds)

    // What one handle's gesture does: twenty-five reports of eight pixels each, every one applied
    // to the range the one before it produced. A handler that kept its first range would answer
    // 80ms to all of them and the handle would stop after the first.
    repeat(25) {
      val moved = scale.timeAt(scale.xOf(range.start) + 8f)
      range = constraint.constrain(TimeRange(moved, range.endExclusive), TrimHandle.Start, DURATION)
    }

    range.start shouldBe 2.seconds
  }

  private companion object {
    val DURATION = 20.seconds
  }
}
