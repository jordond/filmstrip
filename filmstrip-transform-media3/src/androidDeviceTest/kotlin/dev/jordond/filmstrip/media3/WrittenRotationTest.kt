package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Where [Written] reads a point of a frame its container turns, checked on a stored frame whose quadrants each hold
 * their own grey.
 *
 * A point in one quadrant of the shown frame only reads that quadrant's grey when the turn is undone the right way
 * round, so a quarter turn read as three quarters lands in the opposite corner and fails.
 */
class WrittenRotationTest {
  @Test
  fun aTurnedFrameReadsEveryShownQuadrantFromTheStoredOneThatTurnsOntoIt() {
    val stored = Written(QUADRANTS, BT709, fullRange = false)
    // The unturned frame is what every turn is read against, so it has to read each quadrant where it is stored.
    val greys = CLOCKWISE.map { (fx, fy) -> stored.rgbAt(fx, fy).first }
    withClue("the unturned quadrants read $greys clockwise from the top left") {
      greys shouldBe greys.sorted().distinct()
    }

    TURNS.forEach { degrees ->
      val shown = Written(QUADRANTS, BT709, fullRange = false, rotationDegrees = degrees)
      CLOCKWISE.forEachIndexed { at, point ->
        // A clockwise quarter turn moves each quadrant one place on round the clockwise order.
        val from = CLOCKWISE[(at - degrees / QUARTER_TURN).mod(CLOCKWISE.size)]
        withClue("turned $degrees degrees, the shown quadrant at $point") {
          shown.rgbAt(point.first, point.second) shouldBe stored.rgbAt(from.first, from.second)
        }
      }
    }
  }

  private companion object {
    const val BT709 = 1
    const val QUARTER_TURN = 90
    val TURNS = listOf(0, 90, 180, 270)

    // The centre of each quadrant, top left first and on round clockwise.
    val CLOCKWISE = listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.75f to 0.75f, 0.25f to 0.75f)

    // Wider than it is tall, so a turn also swaps which side is long.
    val STORED = Size(64, 32)

    // Video range luma for each quadrant in the clockwise order, far enough apart that no two read alike.
    val LUMA = listOf(40, 100, 160, 220)

    val QUADRANTS: FrameCodes =
      run {
        val luma =
          IntArray(STORED.width * STORED.height) { index ->
            val right = index % STORED.width >= STORED.width / 2
            val bottom = index / STORED.width >= STORED.height / 2
            LUMA[
              when {
                !right && !bottom -> 0
                right && !bottom -> 1
                right && bottom -> 2
                else -> 3
              },
            ]
          }
        val neutral = IntArray(STORED.width / 2 * (STORED.height / 2)) { NEUTRAL_CHROMA }
        FrameCodes(STORED, luma, neutral, neutral.copyOf())
      }

    const val NEUTRAL_CHROMA = 128
  }
}
