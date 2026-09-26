package dev.jordond.filmstrip.media3

import android.graphics.Color
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HLG_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Overlays on an export that keeps its HDR grade, on real media3.
 *
 * `OverlayShaderProgram` sorts overlays into HDR families by type, and checks `TextOverlay` before
 * `BitmapOverlay` because the first extends the second. `RasterOverlay` extends `TextOverlay` to
 * land on the one branch that takes a plain SDR bitmap onto an HDR frame. A plain `BitmapOverlay`
 * there is read as UltraHDR, which wants API 34, wants a gainmap on the bitmap and spends two
 * sampler slots. Nothing in media3 documents that sorting, so these tests are what says it still
 * holds after a bump.
 *
 * The pixel arm is the same difference measurement [AndroidOverlayTest] makes, because
 * `MediaMetadataRetriever` tone-maps an HDR frame on the way out without saying how, and only a
 * region compared against the same region of the same frame of a plain export survives that.
 *
 * Skipped when the fixtures are absent, or on a device that writes no HDR, as in [AndroidHdrTest].
 */
class AndroidOverlayHdrTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aWatermarkLandsOnAKeptPqGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.assumeEncodesHdr()

      val plain = probe.frame(export(source, emptyList()))
      val marked = probe.frame(export(source, listOf(probe.watermark(Corner.BottomEnd))))

      assertTrue(marked.gainedOver(plain, BOTTOM_END) > COVERED, "no watermark on a kept PQ grade")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the watermark bled into the far corner")
    }

  // HLG holds scene light where PQ holds display light, and media3 carries the two through
  // different working spaces, so the transfer the overlay is blended into is a separate answer.
  @Test
  fun aWatermarkLandsOnAKeptHlgGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HLG_CLIP)
      probe.assumeEncodesHdr()

      val plain = probe.frame(export(source, emptyList()))
      val marked = probe.frame(export(source, listOf(probe.watermark(Corner.BottomEnd))))

      assertTrue(marked.gainedOver(plain, BOTTOM_END) > COVERED, "no watermark on a kept HLG grade")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the watermark bled into the far corner")
    }

  @Test
  fun textIsBurnedInOnAKeptGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.assumeEncodesHdr()

      val plain = probe.frame(export(source, emptyList()))
      val captioned = probe.frame(export(source, listOf(caption(Anchor.TopStart))))

      assertTrue(captioned.gainedOver(plain, TOP_START) > COVERED, "no burned-in text on a kept grade")
      assertTrue(captioned.gainedOver(plain, BOTTOM_END) < UNTOUCHED, "the caption reached the opposite corner")
    }

  // Both overlay kinds resolve to the same RasterOverlay, so this is the check that gathering two of
  // them into one OverlayEffect still lands both once the frame is ten-bit.
  @Test
  fun aWatermarkAndTextShareOnePassOnAKeptGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.assumeEncodesHdr()

      val plain = probe.frame(export(source, emptyList()))
      val both = probe.frame(export(source, listOf(probe.watermark(Corner.BottomEnd), caption(Anchor.TopStart))))

      assertTrue(both.gainedOver(plain, BOTTOM_END) > COVERED, "the watermark did not survive sharing a pass")
      assertTrue(both.gainedOver(plain, TOP_START) > COVERED, "the text did not survive sharing a pass")
    }

  @Test
  fun anOverlayDoesNotCostTheGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.assumeEncodesHdr()

      val written = export(source, listOf(probe.watermark(Corner.BottomEnd)))
      val probed = probe.infoOf(MediaSource.of(written.path))
      val video = assertNotNull(probed.video, "the written file has no video track")

      assertTrue(video.hdrTransfer != null, "an overlay on a kept grade wrote an SDR file")
    }

  // The overlay keeps its sRGB values on a BT.2020 frame, which is a real difference from what was
  // authored, so the plan has to say so rather than degrade silently.
  @Test
  fun anOverlayOnAKeptGradeIsReportedAsApproximated() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.assumeEncodesHdr()

      val composition = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(probe.watermark(Corner.BottomEnd)))
      val verdict = probe.filmstrip.plan(composition, SPEC)

      val degraded = assertIs<Verdict.Degraded>(verdict)
      assertTrue(
        degraded.adjustments.any { it.kind == AdjustmentKind.EffectApproximated },
        "an overlay on HDR was planned without reporting its colour space: ${degraded.adjustments}",
      )
    }

  private fun caption(anchor: Anchor) =
    TextOverlay(
      text = "HELLO",
      style = TextStyle(fontSize = 0.18f, color = Color.WHITE, backgroundColor = BADGE_COLOR),
      anchor = anchor,
    )

  private suspend fun export(
    source: MediaSource,
    effects: List<EffectSpec>,
  ): File = probe.export(EditComposition(listOf(Track(listOf(Clip(source)))), effects), SPEC)

  private companion object {
    val SPEC = ExportSpec(targetHeight = 360, hdr = HdrMode.KeepHdr)
  }
}
