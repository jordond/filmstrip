package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserAudioPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the live audio graph anchors its clock and walks the timeline a window at a time.
 *
 * Each scheduling pass reads the tracks once, so counting the reads counts the windows. Whether the
 * browser lets the context run depends on its autoplay policy, so nothing here waits on sound.
 */
class BrowserAudioPreviewTest {
  @Test
  fun aPumpBeforeStartSchedulesNothing() =
    runTest {
      val reads = TrackReads()
      val preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.pump(Duration.ZERO)

        reads.count shouldBe 0
      } finally {
        preview.release()
      }
    }

  // Half a second of look-ahead in quarter-second windows is two passes from a standing start.
  @Test
  fun aStartSchedulesTheLookAheadAWindowAtATime() =
    runTest {
      val reads = TrackReads()
      val preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.start(FROM)

        reads.count shouldBe 2
      } finally {
        preview.release()
      }
    }

  // The playhead a tenth of a second on leaves a tenth of a second the look-ahead has not reached,
  // which is one short window rather than a full one or none.
  @Test
  fun aPumpSchedulesOnlyWhatCameInsideTheLookAheadSinceTheLast() =
    runTest {
      val reads = TrackReads()
      val preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.start(FROM)
        preview.pump(FROM + 100.milliseconds)
        reads.count shouldBe 3

        preview.pump(FROM + 100.milliseconds)
        reads.count shouldBe 3

        preview.pump(FROM + 700.milliseconds)
        reads.count shouldBe 6
      } finally {
        preview.release()
      }
    }

  @Test
  fun aStoppedPreviewSchedulesNothingFurther() =
    runTest {
      val reads = TrackReads()
      val preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.start(FROM)
        preview.stop()
        preview.pump(FROM + 2.seconds)

        reads.count shouldBe 2
      } finally {
        preview.release()
      }
    }

  // The stop lands inside the first of the start's two passes, the way a pause does while a decode
  // is still in flight.
  @Test
  fun aStopDuringAStartSchedulesNoFurtherWindow() =
    runTest {
      lateinit var preview: BrowserAudioPreview
      val reads = TrackReads(onRead = { preview.stop() })
      preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.start(FROM)

        reads.count shouldBe 1
      } finally {
        preview.release()
      }
    }

  @Test
  fun aStartAnchorsThePlayheadWhereItWasAskedFor() =
    runTest {
      val preview = BrowserAudioPreview({ emptyList() }, SourceCache())

      try {
        preview.start(FROM)

        val position = preview.position
        withClue("the playhead read $position after a start at $FROM") {
          (position >= FROM && position < FROM + 1.seconds) shouldBe true
        }
      } finally {
        preview.release()
      }
    }

  @Test
  fun aReleasedPreviewDoesNotStartAgain() =
    runTest {
      val reads = TrackReads()
      val preview = BrowserAudioPreview(reads::tracks, SourceCache())

      preview.release()
      preview.release()
      preview.start(FROM)
      preview.pump(FROM)

      reads.count shouldBe 0
      withClue("a released preview moved its playhead to ${preview.position}") {
        (preview.position < FROM) shouldBe true
      }
    }

  private class TrackReads(
    private val onRead: () -> Unit = {},
  ) {
    var count: Int = 0
      private set

    fun tracks(): List<ResolvedTrack> {
      count++
      onRead()
      return emptyList()
    }
  }

  private companion object {
    val FROM = 5.seconds
  }
}
