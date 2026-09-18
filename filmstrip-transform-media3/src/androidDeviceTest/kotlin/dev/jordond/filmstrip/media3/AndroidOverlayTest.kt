package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.OverlayPlacement
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.effects.overlay.fadeOut
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Overlays, checked against the pixels that were written, not the plan.
 *
 * Every claim here is about where something landed, so each test exports the same clip twice, once
 * plain and once with the overlay, and measures how much more of a region reads as the overlay's
 * colour. Two things make that measurement trustworthy. The overlay is orange, which none of the
 * test pattern's saturated bars come near, where red or green would collide with them. And it is
 * the increase over the same region of the same frame of the plain export that is asserted on, not
 * an absolute count, so whatever the pattern was already drawing there cancels out.
 *
 * The animation tests read the written file at a quarter and at three quarters of a two second ramp
 * and compare what they measure against what the shared sampler answers for the same instant, never
 * against a copied number. The endpoints are left out on purpose: they agree under both an additive
 * and a multiplicative reading of a ramp, so only the middle says whether the whole range is right.
 *
 * Skipped when the fixtures are absent, as in [AndroidExportTest].
 */
class AndroidOverlayTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val filmstrip = Filmstrip(context) { media3Backend() }

  @Test
  fun aWatermarkLandsInTheCornerItNamed() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val plain = frame(export(source, emptyList()))
      val marked = frame(export(source, listOf(watermark(Corner.BottomEnd))))

      assertTrue(marked.gainedOver(plain, BOTTOM_END) > COVERED, "no watermark in the bottom-end corner")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the watermark bled into the far corner")
    }

  @Test
  fun everyCornerIsItsOwnCorner() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val plain = frame(export(source, emptyList()))

      // Each corner covers its own region and leaves the diagonal one alone, which is the only
      // reading under which neither axis is flipped.
      listOf(
        Corner.TopStart to (TOP_START to BOTTOM_END),
        Corner.TopEnd to (TOP_END to BOTTOM_START),
        Corner.BottomStart to (BOTTOM_START to TOP_END),
        Corner.BottomEnd to (BOTTOM_END to TOP_START),
      ).forEach { (corner, regions) ->
        val (its, opposite) = regions
        val marked = frame(export(source, listOf(watermark(corner))))

        assertTrue(marked.gainedOver(plain, its) > COVERED, "$corner did not cover its own corner")
        assertTrue(marked.gainedOver(plain, opposite) < UNTOUCHED, "$corner reached the opposite corner")
      }
    }

  @Test
  fun aMarginHoldsTheWatermarkOffTheEdge() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val plain = frame(export(source, emptyList()))
      val flush = frame(export(source, listOf(watermark(Corner.BottomEnd, margin = 0f))))
      val inset = frame(export(source, listOf(watermark(Corner.BottomEnd, margin = 0.2f))))

      assertTrue(flush.gainedOver(plain, CORNER) > COVERED, "a zero margin should reach the corner pixel")
      assertTrue(inset.gainedOver(plain, CORNER) < UNTOUCHED, "a margin should hold the watermark off the corner")
    }

  @Test
  fun textIsBurnedInWhereItWasAnchored() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val plain = frame(export(source, emptyList()))
      val topStart = frame(export(source, listOf(caption(Anchor.TopStart))))
      val centre = frame(export(source, listOf(caption(Anchor.Center))))

      assertTrue(topStart.gainedOver(plain, TOP_START) > COVERED, "no text at the top-start anchor")
      assertTrue(topStart.gainedOver(plain, BOTTOM_END) < UNTOUCHED, "text reached the opposite corner")
      // The same string at the centre has to move, or the anchor is being ignored.
      assertTrue(centre.gainedOver(plain, MIDDLE) > COVERED, "no text at the centre anchor")
      assertTrue(centre.gainedOver(plain, TOP_START) < UNTOUCHED, "a centred caption stayed in the corner")
    }

  @Test
  fun aWatermarkAndTextShareOnePassAndBothSurvive() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val plain = frame(export(source, emptyList()))
      val both = frame(export(source, listOf(watermark(Corner.BottomEnd), caption(Anchor.TopStart))))

      // The two are gathered into one OverlayEffect, so this is the check that gathering them keeps
      // both without dropping one or stacking them on the same spot.
      assertTrue(both.gainedOver(plain, BOTTOM_END) > COVERED, "the watermark did not survive sharing a pass")
      assertTrue(both.gainedOver(plain, TOP_START) > COVERED, "the text did not survive sharing a pass")
    }

  @Test
  fun aTimedWatermarkIsAbsentOutsideItsWindow() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val plain = export(source, emptyList())
      val timed = watermark(Corner.BottomEnd, visibleDuring = TimeRange.of(Duration.ZERO, WINDOW))
      val written = export(source, listOf(timed))

      // Compared frame for frame at the same timestamps, so the pattern's own animation cancels.
      val inside = frame(written, INSIDE).gainedOver(frame(plain, INSIDE), BOTTOM_END)
      val outside = frame(written, OUTSIDE).gainedOver(frame(plain, OUTSIDE), BOTTOM_END)

      assertTrue(inside > COVERED, "the watermark is absent inside its window")
      assertTrue(outside < UNTOUCHED, "the watermark is still there outside its window")
    }

  @Test
  fun moreOverlaysThanOneGlProgramHoldsStillExport() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      // One GL program has fifteen sampler slots, so this many has to be split across two
      // OverlayEffects. Without the split the shader program's constructor throws on the GL thread
      // and the export dies, which is the whole reason the gathering is chunked.
      val many = List(OVER_THE_CAP) { caption(Anchor(0.5f, (it + 0.5f) / OVER_THE_CAP)) }
      val plain = frame(export(source, emptyList()))

      val crowded = frame(export(source, many))

      // The first and last land either side of the chunk boundary, so both showing up is the check
      // that splitting kept every overlay and dropped no chunk.
      assertTrue(crowded.gainedOver(plain, TOP_BAND) > COVERED, "the first overlay is missing")
      assertTrue(crowded.gainedOver(plain, BOTTOM_BAND) > COVERED, "the last overlay is missing")
    }

  @Test
  fun aClipScopedOverlayIsTheSameSizeAsACompositionScopedOne() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val mark = watermark(Corner.BottomEnd, margin = 0f)
      val onComposition = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(mark))
      val onClip = EditComposition(listOf(Track(listOf(Clip(source, effects = listOf(mark))))))

      val composed = frame(export(onComposition))
      val clipped = frame(export(onClip))

      // The clip is 640 wide and the output 426, and a clip's effects run in its own chain before
      // the size stage pins it to the output frame. Measuring the scale against the output rather
      // than against the frame the overlay is drawn on would make the clip-scoped one two thirds
      // the width of the other. A watermark scale means the same fraction either way.
      val composedSpan = composed.badgeSpan(BADGE_ROW)
      val clippedSpan = clipped.badgeSpan(BADGE_ROW)
      assertTrue(composedSpan > SPAN_FLOOR, "no watermark to measure: $composedSpan")
      assertTrue(
        abs(composedSpan - clippedSpan) < SPAN_TOLERANCE,
        "clip-scoped span $clippedSpan does not match composition-scoped $composedSpan",
      )
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
      val first = fixture() ?: return@runTest
      val second = fixture(CLIP_B) ?: return@runTest
      val plain = frame(export(joined(first, second, emptyList())), IN_SECOND_CLIP)

      // As a control, the same watermark with no window at all has to be visible at the timestamp
      // the other two are read at, or a failure below says nothing about which base was used.
      val always = frame(export(joined(first, second, timed(null))), IN_SECOND_CLIP)
      assertTrue(always.gainedOver(plain, BOTTOM_END) > COVERED, "no clip-scoped watermark to time")

      val onComposition = frame(export(joined(first, second, timed(COMPOSITION_WINDOW))), IN_SECOND_CLIP)
      val onClip = frame(export(joined(first, second, timed(CLIP_WINDOW))), IN_SECOND_CLIP)

      assertTrue(onComposition.gainedOver(plain, BOTTOM_END) > COVERED, "a clip's window is not composition time")
      assertTrue(onClip.gainedOver(plain, BOTTOM_END) < UNTOUCHED, "a clip's window is the clip's own time")
    }

  @Test
  fun aFadedWatermarkIsDrawnAtTheOpacityTheSamplerAnswers() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val still = watermark(Corner.BottomEnd)
      val faded = watermark(Corner.BottomEnd, animation = fadeIn(RAMP))

      val plain = export(source, emptyList())
      val opaque = export(source, listOf(still))
      val written = export(source, listOf(faded))

      RAMP_POINTS.forEach { at ->
        val here = frame(written, at)
        val frameSize = here.frameSize()
        val expected = faded.frameAt(at, SPAN)?.opacity ?: error("the watermark is not drawn at $at")
        val measured =
          here.blendedOver(
            frame(plain, at),
            frame(opaque, at),
            still.placedOn(frameSize, BADGE).core(frameSize),
          )

        assertTrue(
          abs(measured - expected) < ALPHA_TOLERANCE,
          "at $at the fade measured $measured where the sampler says $expected",
        )
      }
    }

  @Test
  fun aFadingWatermarkIsDrawnAtTheOpacityTheRunsEndAnswers() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val still = watermark(Corner.BottomEnd)
      val leaving = watermark(Corner.BottomEnd, animation = fadeOut(RAMP))

      val plain = export(source, emptyList())
      val opaque = export(source, listOf(still))
      val written = export(source, listOf(leaving))

      // A fade out counts back from the run's last frame rather than forward from its first, so
      // this is the reading that says the span's end reached the overlay at all.
      RAMP_POINTS.forEach { at ->
        val here = frame(written, at)
        val frameSize = here.frameSize()
        val expected = leaving.frameAt(at, SPAN)?.opacity ?: error("the watermark is not drawn at $at")
        val measured =
          here.blendedOver(
            frame(plain, at),
            frame(opaque, at),
            still.placedOn(frameSize, BADGE).core(frameSize),
          )

        assertTrue(
          abs(measured - expected) < ALPHA_TOLERANCE,
          "at $at the fade out measured $measured where the sampler says $expected",
        )
      }
    }

  @Test
  fun anUnwindowedClipOverlayIsDrawnOnTheFirstAndLastFrameOfItsSlot() =
    runTest(timeout = TIMEOUT) {
      val first = fixture() ?: return@runTest
      val second = fixture(CLIP_B) ?: return@runTest
      val plain = export(joined(first, second, emptyList()))
      val written = export(joined(first, second, timed(null)))

      // The second clip's slot opens at two seconds and closes at four. Both ends are where a
      // half-open window and a timestamp that rounds the wrong way would drop a frame, and an
      // overlay that named no window has to survive both.
      listOf(SLOT_OPENS, SLOT_CLOSES).forEach { at ->
        val gained = frame(written, at).gainedOver(frame(plain, at), BOTTOM_END)

        assertTrue(gained > COVERED, "no watermark at $at, which is inside the clip's own slot")
      }
    }

  @Test
  fun aSlidingWatermarkSitsWhereTheSharedGeometryPutsIt() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val sliding = watermark(Corner.BottomEnd, animation = slideIn(SLIDE_FROM, RAMP))
      val written = export(source, listOf(sliding))

      RAMP_POINTS.forEach { at ->
        val here = frame(written, at)
        val rect = sliding.rectAt(at, here.frameSize())
        val measured = here.badgeCentre(rect.midY) ?: error("no watermark on the row at $at")

        assertTrue(
          abs(measured - rect.midX) < POSITION_TOLERANCE,
          "at $at the watermark sits at $measured where the offset puts it at ${rect.midX}",
        )
      }
    }

  @Test
  fun aGrowingWatermarkIsDrawnAtTheWidthTheSharedGeometryPutsIt() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val growing = watermark(Corner.BottomEnd, animation = GROW)
      val written = export(source, listOf(growing))

      RAMP_POINTS.forEach { at ->
        val here = frame(written, at)
        val rect = growing.rectAt(at, here.frameSize())
        val measured = here.badgeSpan(rect.midY)

        assertTrue(
          abs(measured - rect.width) < SPAN_TOLERANCE,
          "at $at the watermark spans $measured where the scale makes it ${rect.width}",
        )
      }
    }

  @Test
  fun aWatermarkCarriedOffTheFrameStopsAtMedia3sAnchorLimit() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val leaving = watermark(Corner.BottomEnd, animation = slideIn(PAST_THE_EDGE, RAMP))
      val written = export(source, listOf(leaving))

      val here = frame(written, QUARTER)
      val rect = leaving.rectAt(QUARTER, here.frameSize())
      assertTrue(rect.right > 1f, "this offset does not carry the watermark off the frame: $rect")

      // media3 documents both anchors as @FloatRange(from = -1, to = 1) on
      // StaticOverlaySettings.Builder, so the background anchor is held at the frame's right edge
      // and the watermark stops with its own right edge there. The width still comes from the
      // shared geometry. Apple and ffmpeg let the same slide carry on, which is the one place the
      // three backends are meant to disagree.
      val held = 1f - rect.width / 2f
      val measured = here.badgeCentre(rect.midY) ?: error("the watermark left the frame entirely")

      assertTrue(
        abs(measured - held) < POSITION_TOLERANCE,
        "the clamp left the watermark at $measured where media3's limit puts it at $held",
      )
    }

  @Test
  fun anUnreadableWatermarkIsRefusedWhilePlanning() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val missing = ImageOverlay(ImageSource.of("/does/not/exist.png"), Corner.BottomEnd)

      val verdict = filmstrip.plan(EditComposition(listOf(Track(listOf(Clip(source)))), listOf(missing)), SPEC)

      // Refused before an encoder starts, with no throw on the GL thread mid-export.
      val incapable = assertIs<Verdict.Incapable>(verdict)
      assertTrue(incapable.reasons.any { it.message.contains("decoded") }, "unhelpful refusal: ${incapable.reasons}")
    }

  private fun watermark(
    corner: Corner,
    margin: Float = DEFAULT_MARGIN,
    visibleDuring: TimeRange? = null,
    animation: OverlayAnimation? = null,
  ) = ImageOverlay(ImageSource.of(badgeFile(context).path), corner, margin, BADGE_SCALE, 1f, visibleDuring, animation)

  // Where the shared geometry puts the watermark on the frame written at `at`. The badge is square
  // and the fixture is one clip on the composition, so the frame it is measured against is the one
  // the export wrote.
  private fun ImageOverlay.rectAt(
    at: Duration,
    frame: Size,
  ): NormalizedRect {
    val sampled = frameAt(at, SPAN) ?: error("the watermark is not drawn at $at")
    return placedOn(frame, BADGE).animatedBy(sampled).rectOn(frame)
  }

  // A patch well inside the badge, clear of the edges chroma subsampling smears.
  private fun OverlayPlacement.core(frame: Size): Region {
    val rect = rectOn(frame)
    val insetX = rect.width * EDGE_INSET
    val insetY = rect.height * EDGE_INSET
    return Region(rect.left + insetX, rect.top + insetY, rect.right - insetX, rect.bottom - insetY)
  }

  // Plated in the badge colour instead of the usual dark one, so text is measured the same way a
  // watermark is.
  private fun caption(anchor: Anchor) =
    TextOverlay(
      text = "HELLO",
      style = TextStyle(fontSize = 0.18f, color = Color.WHITE, backgroundColor = BADGE_COLOR),
      anchor = anchor,
    )

  private suspend fun export(
    source: MediaSource,
    effects: List<EffectSpec>,
  ): File = export(EditComposition(listOf(Track(listOf(Clip(source)))), effects))

  private fun timed(window: TimeRange?) = listOf(watermark(Corner.BottomEnd, visibleDuring = window))

  private fun joined(
    first: MediaSource,
    second: MediaSource,
    onSecond: List<EffectSpec>,
  ) = EditComposition(listOf(Track(listOf(Clip(first), Clip(second, effects = onSecond)))))

  private suspend fun export(composition: EditComposition): File {
    val plan =
      when (val verdict = filmstrip.plan(composition, SPEC)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> throw AssertionError("refused: ${verdict.reasons.map { it.message }}")
      }

    val statuses = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.temporary()).toList() }
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) throw AssertionError("export failed: ${finished.error.message}")
    return File((assertIs<ExportStatus.Success>(finished).output as MediaSink.Path).path)
  }

  private fun frame(
    video: File,
    at: Duration = MID_CLIP,
  ): Bitmap = frameOf(video, at)

  private fun fixture(name: String = CLIP): MediaSource? {
    val stream = javaClass.classLoader?.getResourceAsStream(name) ?: return null
    val file = File(context.cacheDir, name)
    stream.use { input -> file.outputStream().use(input::copyTo) }
    return MediaSource.of(file.path)
  }

  private companion object {
    val TIMEOUT = 5.minutes
    val SPEC = ExportSpec(targetHeight = 240)
    val MID_CLIP = 1_000.milliseconds

    val WINDOW = 700.milliseconds
    val INSIDE = 300.milliseconds
    val OUTSIDE = 1_500.milliseconds

    const val CLIP = "android_export_a.mp4"
    const val CLIP_B = "android_export_b.mp4"

    // Both clips run two seconds, so the second one occupies the composition's third second.
    val IN_SECOND_CLIP = 2_300.milliseconds

    // The first frame of the second clip's slot, and the last, which is the frame nearest a time
    // inside the final thirtieth of the composition.
    val SLOT_OPENS = 2_000.milliseconds
    val SLOT_CLOSES = 3_990.milliseconds
    val COMPOSITION_WINDOW = TimeRange.of(2_000.milliseconds, 2_700.milliseconds)
    val CLIP_WINDOW = TimeRange.of(Duration.ZERO, 700.milliseconds)

    const val DEFAULT_MARGIN = 0.02f

    val TOP_START = Region(0f, 0f, 0.3f, 0.3f)
    val TOP_END = Region(0.7f, 0f, 1f, 0.3f)
    val BOTTOM_START = Region(0f, 0.7f, 0.3f, 1f)
    val BOTTOM_END = Region(0.7f, 0.7f, 1f, 1f)
    val MIDDLE = Region(0.35f, 0.4f, 0.65f, 0.6f)

    // Thin full-width bands holding the first and last of a crowd of overlays.
    val TOP_BAND = Region(0.3f, 0.02f, 0.7f, 0.08f)
    val BOTTOM_BAND = Region(0.3f, 0.92f, 0.7f, 0.98f)
    const val OVER_THE_CAP = 20

    // A row through the middle of a bottom-corner badge, and how wide it should read.
    const val BADGE_ROW = 0.75f
    const val SPAN_FLOOR = 0.2f
    const val SPAN_TOLERANCE = 0.06f

    // The last few percent of the frame, which a zero margin reaches and any margin does not.
    val CORNER = Region(0.95f, 0.95f, 1f, 1f)

    val BADGE = Size(BADGE_PX, BADGE_PX)

    // The clip runs two seconds and carries the whole composition, so this is the span the planner
    // hands a composition-scoped effect. A fade out measures against its end, which is where a
    // frame of drift between the fixture's nominal length and its written one would show up, and
    // that is a thirtieth of the ramp against a tolerance twice that.
    val RAMP = 2.seconds
    val SPAN = TimeRange.of(Duration.ZERO, RAMP)
    val QUARTER = 500.milliseconds
    val RAMP_POINTS = listOf(QUARTER, 1_500.milliseconds)

    // Far enough that the two readings are two thirds of a badge width apart, and near enough that
    // the badge stays on the frame at both, which keeps media3's anchor clamp out of this
    // measurement.
    val SLIDE_FROM = OverlayOffset(-0.4f, 0f)

    // Well past the right edge, so the anchor the clamp is asked about is out of range at both ends.
    val PAST_THE_EDGE = OverlayOffset(0.6f, 0f)

    // No built-in helper ramps size, which is the point: a caller writes the curve in the callback.
    val GROW = OverlayAnimation { time -> OverlayFrame(scale = 1f + (time.elapsed / RAMP).toFloat().coerceIn(0f, 1f)) }

    const val ALPHA_TOLERANCE = 0.12f
    const val POSITION_TOLERANCE = 0.05f
    const val EDGE_INSET = 0.25f
  }
}

private fun Bitmap.frameSize() = Size(width, height)

private val NormalizedRect.midX: Float get() = (left + right) / 2f

private val NormalizedRect.midY: Float get() = (top + bottom) / 2f
