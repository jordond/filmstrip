package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.INSIDE_WINDOW
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.OUTSIDE_WINDOW
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.centreOf
import dev.jordond.filmstrip.ffmpeg.support.drawnAt
import dev.jordond.filmstrip.ffmpeg.support.frameTime
import dev.jordond.filmstrip.ffmpeg.support.opensOn
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Overlays shown for a window, read frame by frame to check they open and close where the shared sampler puts them.
 */
class FfmpegOverlayWindowMediaTest {
  private val fixture = FfmpegExportFixture()

  // The window is authored in composition time and the clip's branch counts from its own start, so
  // a window on the second of two clips is drawn a clip's length early unless the lowering moves it.
  @Test
  fun `shows a clip's window where the composition asked for it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val windowed = fixture.watermark().let { mark -> ImageOverlay(mark.image, mark.corner, visibleDuring = WINDOW) }
      val bare = fixture.exportedWatermark("filmstrip-overlay-window-bare", null, clips = 2, onClip = true)
      val marked = fixture.exportedWatermark("filmstrip-overlay-window", windowed, clips = 2, onClip = true)
      val size = marked.plan.output.size
      val probe = centreOf(windowed.placedOn(size, OVERLAY_SIZE).rectOn(size), size)

      marked.plan.duration shouldBe 4.seconds
      withClue("the watermark is missing inside its window") {
        drawnAt(bare, marked, probe, INSIDE_WINDOW).shouldBeTrue()
      }
      OUTSIDE_WINDOW.forEach { at ->
        withClue("the watermark is drawn at $at, outside its window") {
          (!drawnAt(bare, marked, probe, at)).shouldBeTrue()
        }
      }

      bare.file.delete()
      marked.file.delete()
    }

  // The window's own edges, on the output grid rather than a third of a second either side of them.
  // Which frames those are comes off the shared sampler, so the frame sitting on the run's end is
  // outside the window here for the same reason it is on every other backend.
  @Test
  fun `draws a window to its edges and no further`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val windowed = fixture.watermark().let { mark -> ImageOverlay(mark.image, mark.corner, visibleDuring = WINDOW) }
      val bare = fixture.exportedWatermark("filmstrip-overlay-edge-bare", null, clips = 2, onClip = true)
      val marked = fixture.exportedWatermark("filmstrip-overlay-edge", windowed, clips = 2, onClip = true)
      val size = marked.plan.output.size
      val probe = centreOf(windowed.placedOn(size, OVERLAY_SIZE).rectOn(size), size)
      val inside = windowFrames(windowed, marked.plan)

      listOf(inside.first(), inside.last()).forEach { frame ->
        withClue("no watermark on frame $frame") {
          drawnAt(bare, marked, probe, opensOn(frame, marked.plan)).shouldBeTrue()
        }
      }
      listOf(inside.first() - 1, inside.last() + 1).forEach { frame ->
        withClue("a watermark on frame $frame") {
          (!drawnAt(bare, marked, probe, opensOn(frame, marked.plan))).shouldBeTrue()
        }
      }

      bare.file.delete()
      marked.file.delete()
    }

  // A clip's merge sits ahead of the tail's fps, so a clip whose own rate differs from the output's
  // is drawn on its own frames and resampled onto the output grid afterwards. The resampler builds
  // two output frames out of one source frame either side of a boundary, so the pair cannot be
  // drawn differently and an edge lands within one frame of where the sampler puts it. Read a frame
  // in from each edge, which that cannot reach, and a frame out from each, which it cannot either.
  @Test
  fun `opens a resampled clip's window where the shared sampler puts it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val windowed =
        fixture.watermark().let { mark -> ImageOverlay(mark.image, mark.corner, visibleDuring = RESAMPLED_WINDOW) }
      val resampled = ExportSpec(frameRate = RESAMPLED_RATE)
      val bare = fixture.exportedWatermark("filmstrip-overlay-rate-bare", null, onClip = true, spec = resampled)
      val marked = fixture.exportedWatermark("filmstrip-overlay-rate", windowed, onClip = true, spec = resampled)
      val size = marked.plan.output.size
      val probe = centreOf(windowed.placedOn(size, OVERLAY_SIZE).rectOn(size), size)

      // The mismatch is the whole point of the fixture, so it is asserted rather than assumed.
      marked.plan.output.frameRate shouldBe RESAMPLED_RATE
      val inside = windowFrames(windowed, marked.plan)

      listOf(inside.first() + 1, inside.last() - 1).forEach { frame ->
        withClue("no watermark on frame $frame") {
          drawnAt(bare, marked, probe, opensOn(frame, marked.plan)).shouldBeTrue()
        }
      }
      listOf(inside.first() - 2, inside.last() + 2).forEach { frame ->
        withClue("a watermark on frame $frame") {
          (!drawnAt(bare, marked, probe, opensOn(frame, marked.plan))).shouldBeTrue()
        }
      }

      bare.file.delete()
      marked.file.delete()
    }

  /**
   * Which output frames the shared sampler draws [spec] on, as indices on [plan]'s own frame grid.
   *
   * The window a test reads the file against comes from here rather than from arithmetic of its
   * own, so a backend drawing a different frame than every other one fails rather than agreeing
   * with the number a test wrote down beside it.
   */
  private fun windowFrames(
    spec: ImageOverlay,
    plan: ExportPlan,
  ): List<Int> {
    val rate = plan.output.frameRate
    val span = TimeRange.of(Duration.ZERO, plan.duration)
    val frames = (plan.duration.toDouble(DurationUnit.SECONDS) * rate).toInt()

    return (0 until frames).filter { index -> spec.frameAt(frameTime(index, rate), span) != null }
  }

  private companion object {
    // A window over the second of two two-second clips, clear of the join at both ends so a window
    // read against the wrong clock lands outside it rather than on its edge.
    val WINDOW = TimeRange.of(2_500.milliseconds, 3_500.milliseconds)

    // A rate no fixture is encoded at, so a clip keeps its own thirty frames a second through the
    // merge and the tail resamples it afterwards.
    const val RESAMPLED_RATE = 24

    // Edges on neither the source's grid nor the output's, so a window read against the wrong one
    // lands off both.
    val RESAMPLED_WINDOW = TimeRange.of(700.milliseconds, 1_300.milliseconds)
  }
}
