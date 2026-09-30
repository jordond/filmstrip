package dev.jordond.filmstrip.media3

import android.graphics.Color
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.hlgSceneFromNits
import dev.jordond.filmstrip.media.hlgSignalFromScene
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HLG_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.AuthoredColours
import dev.jordond.filmstrip.test.OverlayPixels.hdrSourceOver
import dev.jordond.filmstrip.test.OverlayPixels.pngOf
import dev.jordond.filmstrip.test.assumeEncodesHdr
import dev.jordond.filmstrip.transform.internal.tenBitCodesFromSignal
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
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
 * The level arm reads the ten-bit codes the encoder wrote and holds them to the shared rule: an
 * overlay texel lands on [authoredHdrNits] of its colour and blends in display light, so an opaque
 * one lands where a fill of that colour does.
 *
 * Skipped when the fixtures are absent, or on a device that writes no HDR, as in [AndroidHdrTest].
 */
class AndroidOverlayHdrTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aWatermarkLandsOnAKeptPqGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

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
      probe.filmstrip.assumeEncodesHdr()

      val plain = probe.frame(export(source, emptyList()))
      val marked = probe.frame(export(source, listOf(probe.watermark(Corner.BottomEnd))))

      assertTrue(marked.gainedOver(plain, BOTTOM_END) > COVERED, "no watermark on a kept HLG grade")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the watermark bled into the far corner")
    }

  @Test
  fun textIsBurnedInOnAKeptGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

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
      probe.filmstrip.assumeEncodesHdr()

      val plain = probe.frame(export(source, emptyList()))
      val both = probe.frame(export(source, listOf(probe.watermark(Corner.BottomEnd), caption(Anchor.TopStart))))

      assertTrue(both.gainedOver(plain, BOTTOM_END) > COVERED, "the watermark did not survive sharing a pass")
      assertTrue(both.gainedOver(plain, TOP_START) > COVERED, "the text did not survive sharing a pass")
    }

  @Test
  fun anOverlayDoesNotCostTheGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val written = export(source, listOf(probe.watermark(Corner.BottomEnd)))
      val probed = probe.infoOf(MediaSource.of(written.path))
      val video = assertNotNull(probed.video, "the written file has no video track")

      assertTrue(video.hdrTransfer != null, "an overlay on a kept grade wrote an SDR file")
    }

  // The texels are mapped onto the grade's own light before media3 reads them, so on PQ nothing is left
  // to report.
  @Test
  fun anOverlayOnAKeptPqGradeIsNotReportedAsApproximated() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val verdict = probe.filmstrip.plan(marked(source), SPEC)

      val adjustments = (verdict as? Verdict.Degraded)?.adjustments.orEmpty()
      assertTrue(
        adjustments.none { it.kind == AdjustmentKind.EffectApproximated },
        "an overlay on PQ was reported as approximated: $adjustments",
      )
    }

  // What an overlay reports keys on the grade being written, so a device that could write HDR says
  // nothing about an SDR export.
  @Test
  fun anOverlayOnAnSdrExportFromAnHdrCapableDeviceIsNotReportedAsApproximated() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      probe.filmstrip.assumeEncodesHdr()

      val verdict = probe.filmstrip.plan(marked(source), ExportSpec(targetHeight = 360))

      val adjustments = (verdict as? Verdict.Degraded)?.adjustments.orEmpty()
      assertTrue(
        adjustments.none { it.kind == AdjustmentKind.EffectApproximated },
        "an overlay on an SDR export was reported as approximated: $adjustments",
      )
    }

  // media3 blends an HLG frame in scene light, which a mapping of the texels cannot move, so the plan
  // has to say so rather than degrade silently.
  @Test
  fun anOverlayOnAKeptHlgGradeIsReportedAsBlendingInSceneLight() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HLG_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val degraded = assertIs<Verdict.Degraded>(probe.filmstrip.plan(marked(source), SPEC))

      assertTrue(
        degraded.adjustments.any { it.kind == AdjustmentKind.EffectApproximated },
        "an overlay on HLG was planned without reporting its blend: ${degraded.adjustments}",
      )
    }

  @Test
  fun anOpaqueOverlayOnAKeptPqGradeLandsWhereAFillOfItsColourDoes() =
    runTest(timeout = TIMEOUT) { assertLevels(HDR_CLIP, HdrTransfer.Pq, 1f, ::displayLightBlend) }

  @Test
  fun aHalfOpaqueOverlayOnAKeptPqGradeBlendsInDisplayLight() =
    runTest(timeout = TIMEOUT) { assertLevels(HDR_CLIP, HdrTransfer.Pq, HALF, ::displayLightBlend) }

  @Test
  fun aQuarterOpaqueOverlayOnAKeptPqGradeBlendsInDisplayLight() =
    runTest(timeout = TIMEOUT) { assertLevels(HDR_CLIP, HdrTransfer.Pq, QUARTER, ::displayLightBlend) }

  @Test
  fun anOpaqueOverlayOnAKeptHlgGradeLandsWhereAFillOfItsColourDoes() =
    runTest(timeout = TIMEOUT) { assertLevels(HLG_CLIP, HdrTransfer.Hlg, 1f, ::displayLightBlend) }

  // media3 1.11.1's limit, pinned: a partly covered texel on HLG blends in the scene light media3
  // holds the frame in, not in display light.
  @Test
  fun aHalfOpaqueOverlayOnAKeptHlgGradeBlendsInSceneLight() =
    runTest(timeout = TIMEOUT) { assertLevels(HLG_CLIP, HdrTransfer.Hlg, HALF, ::hlgSceneLightBlend) }

  @Test
  fun aQuarterOpaqueOverlayOnAKeptHlgGradeBlendsInSceneLight() =
    runTest(timeout = TIMEOUT) { assertLevels(HLG_CLIP, HdrTransfer.Hlg, QUARTER, ::hlgSceneLightBlend) }

  // Each overlay sits in the bar a square crop leaves, over a mid grey fill, so the background is a
  // known light rather than a picture. The plain export says the overlay's patch is in the bar at all.
  private suspend fun assertLevels(
    clip: String,
    transfer: HdrTransfer,
    opacity: Float,
    expected: (HdrTransfer, Int, Float) -> IntArray,
  ) {
    val source = probe.fixture(clip)
    probe.filmstrip.assumeEncodesHdr()

    val plain = firstTenBitFrame(probe.export(letterboxed(source, emptyList()), SPEC))
    val (fx, fy) = barMark(WHITE, opacity).centreOn(plain.size)
    assertNear(expected(transfer, FILL_ARGB, 1f), plain.codesAt(fx, fy), "the overlay's patch is not in the bar")

    OVERLAY_COLOURS.forEach { colour ->
      val marked = firstTenBitFrame(probe.export(letterboxed(source, listOf(barMark(colour, opacity))), SPEC))
      assertNear(
        expected(transfer, colour, opacity),
        marked.codesAt(fx, fy),
        "${colour.toUInt().toString(16)} at $opacity on $transfer",
      )
    }
  }

  private fun displayLightBlend(
    transfer: HdrTransfer,
    colour: Int,
    opacity: Float,
  ): IntArray =
    tenBitCodesFromSignal(transfer.signalFromNits(hdrSourceOver(authoredHdrNits(FILL_ARGB), colour, opacity)))

  // Only HLG holds scene light, so the transfer is the one the caller passes for the shape and no other.
  private fun hlgSceneLightBlend(
    transfer: HdrTransfer,
    colour: Int,
    opacity: Float,
  ): IntArray {
    check(transfer == HdrTransfer.Hlg) { "scene light is an HLG figure, not a $transfer one" }
    val over = hlgSceneFromNits(authoredHdrNits(colour))
    val under = hlgSceneFromNits(authoredHdrNits(FILL_ARGB))
    return tenBitCodesFromSignal(FloatArray(3) { hlgSignalFromScene(opacity * over[it] + (1f - opacity) * under[it]) })
  }

  private fun assertNear(
    expected: IntArray,
    actual: IntArray,
    what: String,
  ) {
    val off = expected.indices.maxOf { abs(expected[it] - actual[it]) }
    assertTrue(off <= CODE_TOLERANCE, "$what: wrote ${actual.toList()}, expected ${expected.toList()}, $off codes off")
  }

  private fun barMark(
    colour: Int,
    opacity: Float,
  ) = ImageOverlay(ImageSource.Bytes(pngOf(BADGE, colour)), Corner.TopStart, BAR_MARGIN, BAR_SCALE, opacity)

  private fun ImageOverlay.centreOn(frame: Size): Pair<Float, Float> {
    val core = placedOn(frame, BADGE).core(frame)
    return (core.left + core.right) / 2f to (core.top + core.bottom) / 2f
  }

  private fun letterboxed(
    source: MediaSource,
    effects: List<EffectSpec>,
  ) = EditComposition(
    listOf(Track(listOf(Clip(source)))),
    listOf(Crop(AspectRatio.Square, fit = Fit.Contain)) + effects,
    fill = Fill.Solid(FILL_ARGB),
  )

  private fun marked(source: MediaSource) =
    EditComposition(listOf(Track(listOf(Clip(source)))), listOf(probe.watermark(Corner.BottomEnd)))

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

    const val FILL_ARGB = AuthoredColours.MID_GREY
    const val WHITE = 0xFFFFFFFF.toInt()
    val OVERLAY_COLOURS = listOf(WHITE, AuthoredColours.MID_GREY, AuthoredColours.MID_GREEN)

    const val HALF = 0.5f
    const val QUARTER = 0.25f

    // Small enough that, inset from the top corner, it fits inside the bar a square crop leaves.
    const val BAR_MARGIN = 0.02f
    const val BAR_SCALE = 0.12f

    // The same allowance AppleHdrFillTest gives a ten-bit code, which this suite had no figure for.
    const val CODE_TOLERANCE = 2
  }
}
