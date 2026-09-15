package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.test.LoopingCases
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The one derivation of a repeat schedule, which every backend consumes through the laid list the
 * planner builds from it.
 */
class LaidPassesTest {
  @Test
  fun `a run lays a whole pass for every length that fits and cuts the last`() {
    // Two and a half lengths of room is two whole passes and a cut one, which is what tells this
    // apart from a schedule that rounds the run up or drops what will not fit whole.
    val passes = passesCovering(listOf(1.seconds), 2_500.milliseconds)

    passes.size shouldBe 3
    passes.dropLast(1).forEach { it.length shouldBe 1.seconds }
    passes.last().length shouldBe 500.milliseconds
  }

  @Test
  fun `the passes fill exactly what they were given`() {
    // Anything longer would run the track past the composition, which is the overrun this exists to
    // stop.
    passesCovering(listOf(1.seconds), 2_600.milliseconds)
      .fold(Duration.ZERO) { total, pass -> total + pass.length } shouldBe 2_600.milliseconds
  }

  @Test
  fun `each pass opens where the one before it ended`() {
    val passes = passesCovering(listOf(1_100.milliseconds, 900.milliseconds), 3.seconds)

    passes.first().offset shouldBe Duration.ZERO
    passes.zipWithNext().forEach { (left, right) -> right.offset shouldBe left.offset + left.length }
  }

  @Test
  fun `a run of clips repeats in order rather than one clip at a time`() {
    val passes = passesCovering(listOf(1.seconds, 1.seconds), 3.seconds)

    passes.map { it.index } shouldBe listOf(0, 1, 0)
  }

  @Test
  fun `a fill of the run's own length lays one uncut pass per clip`() {
    val lengths = listOf(1_100.milliseconds, 900.milliseconds, 700.milliseconds)

    val passes = passesCovering(lengths, lengths.fold(Duration.ZERO, Duration::plus))

    passes.map { it.index } shouldBe listOf(0, 1, 2)
    passes.map { it.length } shouldBe lengths
  }

  // The four backend suites build the looping cases from LoopingCases and assert against whatever
  // this function derives for them, so a change to a shared figure has to land somewhere that spells
  // the schedule out. Case one is spelled out on the composition clock in ExportPlannerTest.
  @Test
  fun `case two of the looping suite alternates its two clips until the run is covered`() {
    val passes = passesCovering(LoopingCases.PAIR_LENGTHS, LoopingCases.PRIMARY_RUN - LoopingCases.PAIR_START)

    passes.map { it.index } shouldBe listOf(0, 1, 0, 1, 0, 1, 0)
    passes.map { it.offset } shouldBe
      listOf(
        Duration.ZERO,
        1_100.milliseconds,
        2_000.milliseconds,
        3_100.milliseconds,
        4_000.milliseconds,
        5_100.milliseconds,
        6_000.milliseconds,
      )
    passes.map { it.length } shouldBe
      listOf(
        1_100.milliseconds,
        900.milliseconds,
        1_100.milliseconds,
        900.milliseconds,
        1_100.milliseconds,
        900.milliseconds,
        800.milliseconds,
      )
  }

  @Test
  fun `case three of the looping suite lays three whole passes and a cut one`() {
    val passes = passesCovering(LoopingCases.VIDEO_LENGTHS, LoopingCases.UNDERLAY_RUN)

    passes.map { it.index } shouldBe listOf(0, 0, 0, 0)
    passes.map { it.offset } shouldBe
      listOf(Duration.ZERO, 1_500.milliseconds, 3_000.milliseconds, 4_500.milliseconds)
    passes.map { it.length } shouldBe
      listOf(1_500.milliseconds, 1_500.milliseconds, 1_500.milliseconds, 800.milliseconds)
  }

  @Test
  fun `a run with nothing in it and a fill with nothing to cover both lay nothing`() {
    passesCovering(emptyList(), 3.seconds).shouldBeEmpty()
    passesCovering(listOf(Duration.ZERO), 3.seconds).shouldBeEmpty()
    passesCovering(listOf(1.seconds), Duration.ZERO).shouldBeEmpty()
    passesCovering(listOf(1.seconds), -1.seconds).shouldBeEmpty()
  }
}
