package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.CLIP_B
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * When an overlay is drawn, checked against the pixels that were written, not the plan.
 *
 * Each reading compares a frame of the export carrying the watermark against the frame of a plain
 * export at the same timestamp, and measures how much more of the corner reads as the badge, the
 * same way [AndroidOverlayMediaTest] does. Skipped when the fixtures are absent, as in
 * [AndroidExportMediaTest].
 */
class AndroidOverlayTimingMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aTimedWatermarkIsAbsentOutsideItsWindow() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val plain = probe.export(source, emptyList())
      val timed = probe.watermark(Corner.BottomEnd, visibleDuring = TimeRange.of(Duration.ZERO, WINDOW))
      val written = probe.export(source, listOf(timed))

      // Compared frame for frame at the same timestamps, so the pattern's own animation cancels.
      val inside = probe.frame(written, INSIDE).gainedOver(probe.frame(plain, INSIDE), BOTTOM_END)
      val outside = probe.frame(written, OUTSIDE).gainedOver(probe.frame(plain, OUTSIDE), BOTTOM_END)

      withClue("the watermark is absent inside its window") { (inside > COVERED) shouldBe true }
      withClue("the watermark is still there outside its window") { (outside < UNTOUCHED) shouldBe true }
    }

  // What an effect's window is measured against, for an effect that lives on a clip instead of on
  // the composition. media3 runs a clip's effects in that item's own chain, so the timestamp the
  // overlay is handed there could be either the composition's or the item's, and the two windows
  // below cannot both be right. One covers the start of the second clip counted from the start of
  // the composition, the other covers a stretch the second clip's own two second timeline never
  // reaches. Whichever draws the badge names the base, and it has to be the composition's, which is
  // what the Apple backend hands a FrameInfo and what ffmpeg's `enable` gates on.
  @Test
  fun aTimedClipOverlayIsMeasuredAgainstTheComposition() =
    runTest(timeout = TIMEOUT) {
      val first = probe.fixture()
      val second = probe.fixture(CLIP_B)
      val plain = probe.frame(probe.export(joined(first, second, emptyList())), IN_SECOND_CLIP)

      // As a control, the same watermark with no window at all has to be visible at the timestamp
      // the other two are read at, or a failure below says nothing about which base was used.
      val always = probe.frame(probe.export(joined(first, second, timed(null))), IN_SECOND_CLIP)
      withClue("no clip-scoped watermark to time") { (always.gainedOver(plain, BOTTOM_END) > COVERED) shouldBe true }

      val onComposition = probe.frame(probe.export(joined(first, second, timed(COMPOSITION_WINDOW))), IN_SECOND_CLIP)
      val onClip = probe.frame(probe.export(joined(first, second, timed(CLIP_WINDOW))), IN_SECOND_CLIP)

      withClue("a clip's window is not composition time") {
        (onComposition.gainedOver(plain, BOTTOM_END) > COVERED) shouldBe true
      }
      withClue("a clip's window is the clip's own time") {
        (onClip.gainedOver(plain, BOTTOM_END) < UNTOUCHED) shouldBe true
      }
    }

  @Test
  fun anUnwindowedClipOverlayIsDrawnOnTheFirstAndLastFrameOfItsSlot() =
    runTest(timeout = TIMEOUT) {
      val first = probe.fixture()
      val second = probe.fixture(CLIP_B)
      val plain = probe.export(joined(first, second, emptyList()))
      val written = probe.export(joined(first, second, timed(null)))

      // The second clip's slot opens at two seconds and closes at four. Both ends are where a
      // half-open window and a timestamp that rounds the wrong way would drop a frame, and an
      // overlay that named no window has to survive both.
      listOf(SLOT_OPENS, SLOT_CLOSES).forEach { at ->
        val gained = probe.frame(written, at).gainedOver(probe.frame(plain, at), BOTTOM_END)

        withClue("no watermark at $at, which is inside the clip's own slot") { (gained > COVERED) shouldBe true }
      }
    }

  private fun timed(window: TimeRange?) = listOf(probe.watermark(Corner.BottomEnd, visibleDuring = window))

  private fun joined(
    first: MediaSource,
    second: MediaSource,
    onSecond: List<EffectSpec>,
  ) = Composition(listOf(Track(listOf(Clip(first), Clip(second, effects = onSecond)))))

  private companion object {
    val WINDOW = 700.milliseconds
    val INSIDE = 300.milliseconds
    val OUTSIDE = 1_500.milliseconds

    // Both clips run two seconds, so the second one occupies the composition's third second.
    val IN_SECOND_CLIP = 2_300.milliseconds

    // The first frame of the second clip's slot, and the last, which is the frame nearest a time
    // inside the final thirtieth of the composition.
    val SLOT_OPENS = 2_000.milliseconds
    val SLOT_CLOSES = 3_990.milliseconds
    val COMPOSITION_WINDOW = TimeRange.of(2_000.milliseconds, 2_700.milliseconds)
    val CLIP_WINDOW = TimeRange.of(Duration.ZERO, 700.milliseconds)
  }
}
