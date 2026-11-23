package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.collections.shouldBeSortedBy
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The walk a backend takes up [FLOOR_LADDER] to learn the smallest frame an encoder opens at.
 */
class FloorLadderTest {
  @Test
  fun `the ladder climbs in squares`() {
    FLOOR_LADDER.shouldBeSortedBy { it.width }
    FLOOR_LADDER.forEach { it.width shouldBe it.height }
  }

  @Test
  fun `the first rung that opens is the floor and later rungs are never asked`() {
    val asked = mutableListOf<Size>()
    val middle = FLOOR_LADDER[FLOOR_LADDER.size / 2]

    val floor =
      smallestAccepted { size ->
        asked += size
        size.width >= middle.width
      }

    floor shouldBe middle
    asked shouldBe FLOOR_LADDER.take(FLOOR_LADDER.indexOf(middle) + 1)
  }

  @Test
  fun `an encoder that opens at no rung has no floor`() {
    smallestAccepted { false }.shouldBeNull()
  }
}
