package dev.jordond.filmstrip.internal

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DurationSnapTest {
  @Test
  fun aPositionBetweenGridLinesRoundsDownToTheOneBelow() {
    1_299.milliseconds.snapTo(100.milliseconds) shouldBe 1_200.milliseconds
    1_299.milliseconds.snapTo(250.milliseconds) shouldBe 1_250.milliseconds
    2_999.milliseconds.snapTo(1.seconds) shouldBe 2.seconds
  }

  @Test
  fun aPositionOnAGridLineStaysWhereItIs() {
    1_500.milliseconds.snapTo(250.milliseconds) shouldBe 1_500.milliseconds
    Duration.ZERO.snapTo(250.milliseconds) shouldBe Duration.ZERO
  }

  @Test
  fun aGridThatIsNotPositiveLeavesThePositionAlone() {
    val position = 1_234_567.microseconds

    position.snapTo(Duration.ZERO) shouldBe position
    position.snapTo((-100).milliseconds) shouldBe position
  }

  // The grid is counted in whole milliseconds, so one finer than that counts as no grid at all.
  @Test
  fun aGridFinerThanAMillisecondLeavesThePositionAlone() {
    val position = 1_234_567.microseconds

    position.snapTo(500.microseconds) shouldBe position
  }

  @Test
  fun aSubMillisecondRemainderIsDroppedWithTheRest() {
    1_234_567.microseconds.snapTo(100.milliseconds) shouldBe 1_200.milliseconds
  }

  @Test
  fun aNegativePositionRoundsTowardsTheEarlierGridLine() {
    (-150).milliseconds.snapTo(100.milliseconds) shouldBe (-200).milliseconds
  }
}
