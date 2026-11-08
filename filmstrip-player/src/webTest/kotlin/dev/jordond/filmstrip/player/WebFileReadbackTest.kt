package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.player.contract.contractTest
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reading an exported file back, which the web parity suites compare a preview against.
 */
class WebFileReadbackTest {
  /**
   * One frame at twice the fixture's rate, with no audio track to pad the file out.
   *
   * The file is a single 60 fps frame long, so read at the default 30 fps it would start and end on one output slot.
   */
  @Test
  fun `a one frame file with no audio reads back at the rate it was written at`() =
    contractTest {
      val base = webFixtureComposition()
      val oneFrame = base.withClips(base.clips.map { it.withTrim(TimeRange.of(Duration.ZERO, 1.seconds / FAST_RATE)) })

      val file = webExported(oneFrame, frameRate = FAST_RATE)

      file.frameRate shouldBe FAST_RATE
      webFileFrame(file, Duration.ZERO).size shouldBe FIXTURE_FRAME
    }

  private companion object {
    const val FAST_RATE = FIXTURE_FRAME_RATE * 2
  }
}
