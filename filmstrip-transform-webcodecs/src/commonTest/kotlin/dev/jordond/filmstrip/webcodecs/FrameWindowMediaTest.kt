package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.FrameStream
import dev.jordond.filmstrip.webcodecs.internal.FrameWindow
import dev.jordond.filmstrip.webcodecs.internal.NO_CLIP
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * The decoded frames a preview holds ahead of its playhead, read off a generated clip.
 *
 * Every case ends by clearing the window and counting what it closed against what it opened, since a
 * frame the window loses track of is a decoder frame nobody ever releases.
 */
class FrameWindowMediaTest {
  @Test
  fun fillingStopsAtTheWindowsCapacity() =
    runTest {
      val window = seated(FrameWindow(CAPACITY))

      window.fill()

      window.size shouldBe CAPACITY
      window.opened shouldBe CAPACITY
      window.holds(CLIP) shouldBe true
      window.assertClearedClean()
    }

  // A quarter of a frame either side of the seventh frame, so the nearer of the two straddling it is
  // never a tie. Each advance drops what the one before it left behind.
  @Test
  fun advancingDropsWhatThePlayheadLeftAndAnswersWithTheNearerFrame() =
    runTest {
      val window = seated(FrameWindow(WIDE))
      val early = frameUs(SEVENTH) + FRAME_US / 4
      val late = frameUs(SEVENTH) + FRAME_US * 3 / 4

      window.advanceTo(frameUs(3) + FRAME_US / 4)
      window.closed shouldBe 0
      window.advanceTo(early)

      window.closed shouldBe 4
      assertNear(frameUs(SEVENTH), window.nearest(early).shouldNotBeNull().microsecondTimestamp)
      assertNear(frameUs(SEVENTH + 1), window.nearest(late).shouldNotBeNull().microsecondTimestamp)
      window.assertClearedClean()
    }

  // An advance never decodes past the window's own bound, however far ahead the playhead is.
  @Test
  fun advancingStopsAtTheWindowsCapacity() =
    runTest {
      val window = seated(FrameWindow(CAPACITY))

      window.advanceTo(frameUs(SEVENTH))

      window.size shouldBe CAPACITY
      assertNear(frameUs(CAPACITY - 1), window.nearest(frameUs(SEVENTH)).shouldNotBeNull().microsecondTimestamp)
      window.assertClearedClean()
    }

  // The bound leaves an older frame in hand, which is not what an export draws there, so the window says it fell short.
  @Test
  fun aWindowStoppedAtItsBoundDoesNotReachThePlayhead() =
    runTest {
      val window = seated(FrameWindow(CAPACITY))

      window.advanceTo(frameUs(SEVENTH))
      window.reaches(frameUs(SEVENTH)) shouldBe false
      window.reaches(frameUs(CAPACITY - 2)) shouldBe true

      window.assertClearedClean()
    }

  @Test
  fun aDrainedWindowReachesEveryPlayheadPastItsEnd() =
    runTest {
      val window = seated(FrameWindow(CAPACITY))

      repeat(FRAMES) { window.advanceTo(frameUs(FRAMES + 5)) }
      window.reaches(frameUs(FRAMES + 5)) shouldBe true

      window.assertClearedClean()
    }

  @Test
  fun aPlayheadBehindEverythingHeldFindsNoFrame() =
    runTest {
      val window = seated(FrameWindow(WIDE))
      window.advanceTo(frameUs(3) + FRAME_US / 4)
      window.advanceTo(frameUs(SEVENTH) + FRAME_US / 4)

      window.startsAfter(frameUs(2)) shouldBe true
      window.nearest(frameUs(2)).shouldBeNull()
      window.startsAfter(frameUs(SEVENTH)) shouldBe false
      window.assertClearedClean()
    }

  // Past the last frame the window keeps that frame as the answer, and still reads the clip.
  @Test
  fun aDrainedClipKeepsItsLastFrame() =
    runTest {
      val window = seated(FrameWindow(CAPACITY))

      repeat(FRAMES) { window.advanceTo(frameUs(FRAMES + 5)) }

      window.size shouldBe 1
      assertNear(frameUs(FRAMES - 1), window.nearest(frameUs(FRAMES + 5)).shouldNotBeNull().microsecondTimestamp)
      window.holds(CLIP) shouldBe true
      window.holds(CLIP + 1) shouldBe false
      window.assertClearedClean()
    }

  @Test
  fun seatingAnotherClipClosesEverythingTheLastOneHeld() =
    runTest {
      val window = seated(FrameWindow(CAPACITY))
      window.fill()

      window.seat(CLIP + 1, stream())

      window.clipIndex shouldBe CLIP + 1
      window.size shouldBe 0
      window.closed shouldBe window.opened
      window.holds(CLIP) shouldBe false
      window.assertClearedClean()
    }

  private suspend fun seated(window: FrameWindow): FrameWindow = window.also { it.seat(CLIP, stream()) }

  private suspend fun stream(): FrameStream {
    val reader =
      withClue("the generated clip did not open") {
        SourceReader.of(MediaSource.Bytes(clip())).shouldNotBeNull()
      }
    return withClue("the generated clip has no video") {
      reader.frames(0.0, Double.POSITIVE_INFINITY).shouldNotBeNull()
    }
  }

  private suspend fun clip(): ByteArray = bytes ?: makeClip(frames = FRAMES, frameRate = RATE).also { bytes = it }

  private fun FrameWindow.assertClearedClean() {
    clear()
    size shouldBe 0
    clipIndex shouldBe NO_CLIP
    withClue("the window opened $opened frames and closed $closed") { closed shouldBe opened }
  }

  private fun assertNear(
    expected: Double,
    actual: Double,
  ) {
    withClue("expected a frame at $expected, got $actual") { (abs(expected - actual) < FRAME_US / 4) shouldBe true }
  }

  private fun frameUs(index: Int): Double = index * FRAME_US

  private companion object {
    const val FRAMES = 12
    const val RATE = 30
    const val CAPACITY = 4
    const val WIDE = 8
    const val CLIP = 2
    const val SEVENTH = 6
    const val FRAME_US = 1_000_000.0 / RATE
    var bytes: ByteArray? = null
  }
}
