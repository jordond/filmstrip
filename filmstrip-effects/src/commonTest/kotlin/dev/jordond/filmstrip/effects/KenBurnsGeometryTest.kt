package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.motion.paced
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one interpolation every backend draws a pan from.
 *
 * The ends agree under a linear reading and under every eased one, so what these assert is the
 * middle. Two backends each easing a pan of their own would pass an end-only test and put the same
 * clip in two places at the same time.
 */
class KenBurnsGeometryTest {
  @Test
  fun holdsTheStartRegionAtTheStartOfTheSpan() {
    assertRectEquals(FROM, PUSH_IN.regionAt(SPAN_START, SPAN))
  }

  @Test
  fun holdsTheEndRegionAtTheEndOfTheSpan() {
    assertRectEquals(TO, PUSH_IN.regionAt(SPAN_START + SPAN_LENGTH, SPAN))
  }

  @Test
  fun travelsLinearlyThroughTheMiddleOfTheSpan() {
    val pan = KenBurns(FROM, TO, Easing.Linear)

    // 40% and 60%, so a curve that is symmetric about the halfway point cannot pass by landing on
    // it, and neither can an implementation that only interpolates the two ends.
    assertRectEquals(rectAt(0.4f), pan.regionAt(at(0.4), SPAN))
    assertRectEquals(rectAt(0.5f), pan.regionAt(at(0.5), SPAN))
    assertRectEquals(rectAt(0.6f), pan.regionAt(at(0.6), SPAN))
  }

  @Test
  fun pacesTheMiddleOfTheSpanAlongTheCurve() {
    Easing.entries.forEach { easing ->
      val pan = KenBurns(FROM, TO, easing)

      listOf(0.4, 0.5, 0.6).forEach { fraction ->
        assertRectEquals(
          rectAt(easing.paced(fraction.toFloat())),
          pan.regionAt(at(fraction), SPAN),
          "$easing at $fraction",
        )
      }
    }
  }

  /**
   * The curves are what separates one easing from another, so they have to actually differ.
   */
  @Test
  fun aCurvedPanIsSomewhereElseThanALinearOneAtTheSameTime() {
    val linear = KenBurns(FROM, TO, Easing.Linear).regionAt(at(0.4), SPAN)
    val easedIn = KenBurns(FROM, TO, Easing.EaseIn).regionAt(at(0.4), SPAN)
    val easedOut = KenBurns(FROM, TO, Easing.EaseOut).regionAt(at(0.4), SPAN)

    assertTrue(abs(easedIn.left - linear.left) > TOLERANCE, "EaseIn matched Linear at 40%")
    assertTrue(abs(easedOut.left - linear.left) > TOLERANCE, "EaseOut matched Linear at 40%")
    assertTrue(easedIn.left < linear.left, "EaseIn should be behind Linear at 40%")
    assertTrue(easedOut.left > linear.left, "EaseOut should be ahead of Linear at 40%")
  }

  /**
   * The span is composition-relative on every backend, so a pan on the second clip of a track is
   * measured from where that clip starts rather than from zero.
   */
  @Test
  fun measuresTheSpanFromWhereTheClipStartsOnTheTimeline() {
    val late = TimeRange.of(9.seconds, 14.seconds)

    assertRectEquals(FROM, PUSH_IN.regionAt(9.seconds, late))
    assertRectEquals(rectAt(PUSH_IN.easing.paced(0.4f)), PUSH_IN.regionAt(11.seconds, late))
    assertRectEquals(TO, PUSH_IN.regionAt(14.seconds, late))
  }

  @Test
  fun holdsTheNearestEndOutsideTheSpan() {
    assertRectEquals(FROM, PUSH_IN.regionAt(SPAN_START - 1.seconds, SPAN))
    assertRectEquals(TO, PUSH_IN.regionAt(SPAN_START + SPAN_LENGTH + 1.seconds, SPAN))
  }

  @Test
  fun holdsTheStartRegionOverASpanWithNoLength() {
    assertRectEquals(FROM, PUSH_IN.regionAt(SPAN_START, TimeRange.of(SPAN_START, SPAN_START)))
    assertRectEquals(FROM, PUSH_IN.regionAt(SPAN_START, TimeRange.from(SPAN_START)))
  }

  private fun at(fraction: Double): Duration = SPAN_START + SPAN_LENGTH * fraction

  private fun rectAt(eased: Float): NormalizedRect =
    NormalizedRect(
      left = FROM.left + (TO.left - FROM.left) * eased,
      top = FROM.top + (TO.top - FROM.top) * eased,
      right = FROM.right + (TO.right - FROM.right) * eased,
      bottom = FROM.bottom + (TO.bottom - FROM.bottom) * eased,
    )

  private fun assertRectEquals(
    expected: NormalizedRect,
    actual: NormalizedRect,
    message: String = "",
  ) {
    assertEquals(expected.left, actual.left, TOLERANCE, "left $message")
    assertEquals(expected.top, actual.top, TOLERANCE, "top $message")
    assertEquals(expected.right, actual.right, TOLERANCE, "right $message")
    assertEquals(expected.bottom, actual.bottom, TOLERANCE, "bottom $message")
  }

  private companion object {
    val FROM = NormalizedRect(0f, 0f, 1f, 1f)
    val TO = NormalizedRect(0.25f, 0.2f, 0.85f, 0.8f)
    val PUSH_IN = KenBurns(FROM, TO)

    val SPAN_START = 2.seconds
    val SPAN_LENGTH = 5.seconds
    val SPAN = TimeRange.of(SPAN_START, SPAN_START + SPAN_LENGTH)

    const val TOLERANCE = 1e-4f
  }
}
