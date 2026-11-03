package dev.jordond.filmstrip.effects.geometry

import android.graphics.Matrix
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The vertex transform [KenBurnsTransformation] hands media3 for each frame of a pan.
 *
 * The region [regionAt] names at a frame's presentation time has to fill the frame, so its corners are carried onto
 * the corners of the vertex shader's `[-1, 1]` square, top-left to `(-1, 1)` and bottom-right to `(1, -1)`. Read away
 * from both ends of the span and on a curve, since the ends agree under every reading of the travel.
 */
class KenBurnsTransformationTest {
  @Test
  fun eachFrameDrawsTheRegionTheSharedInterpolationNamesOverTheWholeFrame() {
    for (easing in listOf(Easing.Linear, Easing.EaseInOut)) {
      val pan = KenBurns(FROM, TO, easing)
      val transformation = KenBurnsTransformation(pan, SPAN)
      for (fraction in listOf(0.0, 0.4, 0.5, 0.6, 1.0)) {
        val at = SPAN.start + LENGTH * fraction
        assertFills(pan.regionAt(at, SPAN), transformation.getMatrix(at.inWholeMicroseconds), "$easing at $fraction")
      }
    }
  }

  // media3 compares the matrix it is handed against the one it last uploaded, so a reused instance reads as unchanged.
  @Test
  fun everyFrameIsHandedAMatrixOfItsOwn() {
    val transformation = KenBurnsTransformation(KenBurns(FROM, TO), SPAN)
    val at = (SPAN.start + LENGTH / 2).inWholeMicroseconds

    val first = transformation.getMatrix(at)
    val second = transformation.getMatrix(at)
    second shouldNotBeSameInstanceAs first
    second shouldBe first
  }

  @Test
  fun aFrameOutsideTheSpanHoldsTheEndItIsNearest() {
    val transformation = KenBurnsTransformation(KenBurns(FROM, TO, Easing.Linear), SPAN)

    assertFills(FROM, transformation.getMatrix((SPAN.start - 1.microseconds).inWholeMicroseconds), "before")
    assertFills(TO, transformation.getMatrix((SPAN.start + LENGTH + 1.seconds).inWholeMicroseconds), "after")
  }

  private fun assertFills(
    region: NormalizedRect,
    matrix: Matrix,
    message: String,
  ) {
    val corners =
      floatArrayOf(2f * region.left - 1f, 1f - 2f * region.top, 2f * region.right - 1f, 1f - 2f * region.bottom)
    matrix.mapPoints(corners)
    assertNear(floatArrayOf(-1f, 1f, 1f, -1f), corners, message)
  }

  private fun assertNear(
    expected: FloatArray,
    actual: FloatArray,
    message: String,
  ) {
    expected.indices.forEach {
      withClue("$message: ${actual.toList()}") { (abs(expected[it] - actual[it]) < TOLERANCE) shouldBe true }
    }
  }

  private companion object {
    // The clip sits two seconds into the composition, so a presentation time read from the clip's own start fails.
    val SPAN = TimeRange.of(2.seconds, 6.seconds)
    val LENGTH = 4.seconds
    val FROM = NormalizedRect(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.7f)
    val TO = NormalizedRect(left = 0.35f, top = 0.05f, right = 0.95f, bottom = 0.45f)
    const val TOLERANCE = 1e-4f
  }
}
