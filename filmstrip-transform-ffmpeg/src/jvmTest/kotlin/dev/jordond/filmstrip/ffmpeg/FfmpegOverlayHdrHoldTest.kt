package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREY
import dev.jordond.filmstrip.test.HdrOverlayCells.BLACK
import dev.jordond.filmstrip.test.HdrOverlayCells.WHITE
import dev.jordond.filmstrip.test.OverlayPixels.hdrSourceOver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Still image overlays on a kept HDR grade, read back across the whole of an export rather than on its first frame.
 *
 * A still overlay's picture is read once and held under every frame the blend runs on, so each one is read on the
 * first, the middle and the last output frame it covers. A windowed one is read either side of its window as well. The
 * HDR fixtures are contained in a portrait frame, so a composition overlay sits over the flat top letterbox bar. Every
 * expected code comes from [authoredHdrNits], [hdrSourceOver], [signalFromNits] and [tenBitCodesFromSignal].
 */
class FfmpegOverlayHdrHoldTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a still overlay on a kept PQ grade is drawn on every frame of a two clip export`() =
    runTest(timeout = TIMEOUT) { assertHeld(HdrTransfer.Pq) }

  @Test
  fun `a still overlay on a kept HLG grade is drawn on every frame of a two clip export`() =
    runTest(timeout = TIMEOUT) { assertHeld(HdrTransfer.Hlg) }

  @Test
  fun `a windowed overlay on a kept PQ grade is drawn inside its window and nowhere else`() =
    runTest(timeout = TIMEOUT) { assertWindowed(HdrTransfer.Pq) }

  @Test
  fun `a windowed overlay on a kept HLG grade is drawn inside its window and nowhere else`() =
    runTest(timeout = TIMEOUT) { assertWindowed(HdrTransfer.Hlg) }

  // Two clips, a translucent composition overlay over an opaque one on the bar, and an opaque overlay on the second
  // clip alone. The clip's overlay is drawn on the clip's own frame, which the portrait crop then contains, so its
  // probe is carried through the same contain. What sits under it is the fixture's picture, which an opaque texel
  // replaces outright. The first clip is read against the same export without the clip's overlay, which is what the
  // picture there lands on, once that picture is shown to sit far enough from the overlay to tell the two apart.
  private suspend fun assertHeld(transfer: HdrTransfer) {
    if (!fixture.encodesHdr()) return
    val onClip = overlay(MID_GREEN)
    val onBar = listOf(overlay(MID_GREY), overlay(WHITE, HALF))
    val exported = exported(transfer, onBar, clips = 2, onLastClip = onClip)
    val plan = exported.plan
    val rate = assertNotNull(plan.output.frameRate)
    val frames = framesIn(plan.duration, rate)
    val clipFrames = framesIn(CLIP_LENGTH, rate)
    assertEquals(CLIP_LENGTH * 2, plan.duration)

    val bar = barProbe(plan)
    val blended = expectedCodes(transfer, MID_GREY, WHITE, HALF)
    for (index in listOf(0, frames / 2, frames - 1)) {
      val read = tenBitCodesAt(exported.file, bar, opensOn(index, plan))
      assertCodes(read, blended, "$transfer composition overlay on frame $index of $frames")
    }

    val clip = clipProbe(onClip, plan)
    val opaque = expectedCodes(transfer, MID_GREY, MID_GREEN, 1f)
    for (index in listOf(clipFrames, clipFrames + (frames - clipFrames) / 2, frames - 1)) {
      val read = tenBitCodesAt(exported.file, clip, opensOn(index, plan))
      assertCodes(read, opaque, "$transfer clip overlay on frame $index of $frames")
    }
    val bare = exported(transfer, onBar, clips = 2)
    val firstClip = opensOn(clipFrames / 2, plan)
    val picture = tenBitCodesAt(bare.file, clip, firstClip)
    assertTrue(
      picture.indices.any { abs(picture[it] - opaque[it]) > 2 * CODE_TOLERANCE },
      "$transfer first clip's picture read ${picture.toList()}, too close to the overlay's ${opaque.toList()} to " +
        "tell it from one drawn there",
    )
    assertCodes(tenBitCodesAt(exported.file, clip, firstClip), picture, "$transfer first clip under the clip's overlay")

    listOf(exported, bare).forEach { it.file.delete() }
  }

  // A translucent overlay shown for a window, over an opaque one that is always drawn, so both sides of each edge land
  // on the rule. Which frames the window covers comes off the shared sampler.
  private suspend fun assertWindowed(transfer: HdrTransfer) {
    if (!fixture.encodesHdr()) return
    val windowed =
      ImageOverlay(
        image = ImageSource.ofBytes(pngOf(WHITE, OVERLAY_SIZE)),
        corner = Corner.TopStart,
        opacity = HALF,
        visibleDuring = WINDOW,
      )
    val exported = exported(transfer, listOf(overlay(MID_GREY), windowed))
    val plan = exported.plan
    val rate = assertNotNull(plan.output.frameRate)
    val frames = framesIn(plan.duration, rate)
    val span = TimeRange.of(Duration.ZERO, plan.duration)
    val inside = (0 until frames).filter { windowed.frameAt(frameTime(it, rate), span) != null }
    assertTrue(inside.first() > 1 && inside.last() < frames - 2, "the window $inside reaches an end of the export")

    val probe = barProbe(plan)
    val drawn = expectedCodes(transfer, MID_GREY, WHITE, HALF)
    val bare = expectedCodes(transfer, MID_GREY, MID_GREY, 1f)
    for (index in listOf(inside.first(), inside[inside.size / 2], inside.last())) {
      val read = tenBitCodesAt(exported.file, probe, opensOn(index, plan))
      assertCodes(read, drawn, "$transfer inside, frame $index")
    }
    for (index in listOf(0, inside.first() - 1, inside.last() + 1, frames - 1)) {
      assertCodes(tenBitCodesAt(exported.file, probe, opensOn(index, plan)), bare, "$transfer outside, frame $index")
    }

    exported.file.delete()
  }

  private fun expectedCodes(
    transfer: HdrTransfer,
    background: Int,
    colour: Int,
    alpha: Float,
  ): IntArray =
    tenBitCodesFromSignal(transfer.signalFromNits(hdrSourceOver(authoredHdrNits(background), colour, alpha)))

  private fun assertCodes(
    read: IntArray,
    expected: IntArray,
    what: String,
  ) {
    assertTrue(
      read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE },
      "$what read ${read.toList()}, expected ${expected.toList()}",
    )
  }

  private fun framesIn(
    length: Duration,
    rate: Int,
  ): Int = (length.toDouble(DurationUnit.SECONDS) * rate).roundToInt()

  private fun overlay(
    colour: Int,
    opacity: Float = 1f,
  ): ImageOverlay = ImageOverlay(ImageSource.ofBytes(pngOf(colour, OVERLAY_SIZE)), Corner.TopStart, opacity = opacity)

  // The centre of a composition overlay in the top corner, which sits inside the top letterbox bar.
  private fun barProbe(plan: ExportPlan): Pair<Int, Int> {
    val size = plan.output.size
    val rect = drawnRect(overlay(WHITE), size, TimeRange.of(Duration.ZERO, plan.duration), Duration.ZERO)
    val content = containedRect(FIXTURE_SIZE, size)
    assertTrue(rect.bottom < content.top, "the overlay at $rect is not inside the bar above $content")
    return centreOf(rect, size)
  }

  // The centre of a clip overlay drawn on the fixture's own frame, carried onto the output through the contain.
  private fun clipProbe(
    spec: ImageOverlay,
    plan: ExportPlan,
  ): Pair<Int, Int> {
    val rect = drawnRect(spec, FIXTURE_SIZE, TimeRange.of(Duration.ZERO, CLIP_LENGTH), Duration.ZERO)
    val content = containedRect(FIXTURE_SIZE, plan.output.size)
    return pointIn(
      content.left + (rect.left + rect.width / 2) * content.width,
      content.top + (rect.top + rect.height / 2) * content.height,
      plan.output.size,
    )
  }

  private suspend fun exported(
    transfer: HdrTransfer,
    overlays: List<ImageOverlay>,
    clips: Int = 1,
    onLastClip: ImageOverlay? = null,
  ): ExportedOverlay {
    val source = if (transfer == HdrTransfer.Pq) fixture.hdr else fixture.hdrHlg
    val output = File.createTempFile("filmstrip-hdr-overlay-hold", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        track {
          repeat(clips) { index ->
            clip(MediaSource.of(source.absolutePath)) {
              if (index == clips - 1) onLastClip?.let { mark -> effects { add(mark) } }
            }
          }
        }
        effects {
          crop(AspectRatio.Portrait, fit = Fit.Contain)
          overlays.forEach { add(it) }
        }
        fill(Fill.Solid(BLACK))
      }
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, ExportSpec(hdr = HdrMode.KeepHdr))) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      }
    val finished =
      fixture.filmstrip
        .export(plan, MediaSink.of(output.absolutePath))
        .toList()
        .last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    assertIs<ExportStatus.Success>(finished)
    return ExportedOverlay(output, plan)
  }

  private companion object {
    // The HDR fixtures' frame and length.
    val FIXTURE_SIZE = Size(1280, 720)
    val CLIP_LENGTH = 2.seconds

    // Clear of both ends of one fixture, and off the output grid, so a window read against the wrong clock lands off
    // its edges.
    val WINDOW = TimeRange.of(510.milliseconds, 1_490.milliseconds)
  }
}
