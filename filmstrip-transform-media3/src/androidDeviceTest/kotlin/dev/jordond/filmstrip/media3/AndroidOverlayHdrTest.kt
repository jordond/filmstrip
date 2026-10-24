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
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HLG_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.OverlayPixels.pngOf
import dev.jordond.filmstrip.test.assumeEncodesHdr
import kotlinx.coroutines.test.runTest
import org.junit.AssumptionViolatedException
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
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
 * The levels each overlay cell lands on are read by [AndroidOverlayHdrLevelTest].
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
  fun theBlendBoundCanTellAWrongBlend() =
    assertTrue(
      PATCH_NOISE <= HdrOverlayCells.MAX_BLEND_TOLERANCE,
      "a bound of $PATCH_NOISE codes passes a wrong blend, ${HdrOverlayCells.MAX_BLEND_TOLERANCE} is the most",
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
}

/**
 * The ten-bit codes media3 writes for every cell of [HdrOverlayCells] over one fill on one kept grade.
 *
 * A run covers one fill of [HdrOverlayCells.CELLS] on one transfer, PQ or HLG, and exports every cell over that fill.
 * Each reading is held to [HdrOverlayCells.Cell.codes], where an overlay texel lands on [authoredHdrNits] of its colour
 * and blends in display light, so an opaque one lands where a fill of that colour does. On HLG the blend cells read
 * media3's scene light blend instead. A blend that misses is reported next to where the cell's signal blend and sRGB
 * blend land, so the failure says which wrong blend ran.
 *
 * Skipped when the fixtures are absent, or on a device that writes no HDR, as in [AndroidHdrTest].
 */
@RunWith(Parameterized::class)
class AndroidOverlayHdrLevelTest(
  private val grade: Grade,
) {
  private val probe = Media3ExportProbe()

  /**
   * One kept grade the level test exports, cut from [clip] with [transfer] and letterboxed over [fill].
   *
   * Its string form names the run, as the transfer and the fill's packed sRGB in hex.
   */
  data class Grade(
    val clip: String,
    val transfer: HdrTransfer,
    val fill: Int,
  ) {
    override fun toString(): String = "$transfer over ${fill.hex()}"
  }

  // Each overlay sits in the bar a square crop leaves, over the fill, so the background is a known
  // light rather than a picture. The plain export says the overlay's patch is in the bar at all.
  @Test
  fun everyOverlayCellLandsOnItsPqOrHlgLevel() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(grade.clip)
      probe.filmstrip.assumeEncodesHdr()

      val cells = HdrOverlayCells.CELLS.filter { it.background == grade.fill }
      val plain = firstTenBitFrame(probe.export(letterboxed(source, emptyList()), SPEC))
      val (fx, fy) = barMark(cells.first()).centreOn(plain.size)
      val inBar =
        miss(
          tenBitCodesFromSignal(grade.transfer.signalFromNits(authoredHdrNits(grade.fill))),
          plain.codesAt(fx, fy),
          "the overlay's patch is not in the bar over ${grade.fill.hex()}",
        )
      val misses = if (inBar != null) listOf(inBar) else cells.mapNotNull { missOf(source, it, fx, fy) }

      assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

  // A throw on one cell's export or frame read is that cell's miss, so the cells already read still report. That
  // includes the AssertionError Media3ExportProbe throws for a failed export, status or plan.
  private suspend fun missOf(
    source: MediaSource,
    cell: HdrOverlayCells.Cell,
    fx: Float,
    fy: Float,
  ): String? {
    val what = "${cell.overlay.hex()} at ${cell.opacity} over ${cell.background.hex()} on ${grade.transfer}"
    val reading =
      try {
        firstTenBitFrame(probe.export(letterboxed(source, listOf(barMark(cell))), SPEC)).codesAt(fx, fy)
      } catch (e: AssumptionViolatedException) {
        throw e
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return threw(what, e)
      } catch (e: AssertionError) {
        return threw(what, e)
      }
    val (expected, label) = expectedOf(cell, what)
    return miss(expected, reading, label)?.plus(wrongBlendsOf(cell))
  }

  private fun wrongBlendsOf(cell: HdrOverlayCells.Cell): String =
    if (cell.isBlend) {
      ", a signal blend lands at ${cell.signalBlendCodes(grade.transfer).toList()} and an sRGB blend at " +
        "${cell.srgbBlendCodes(grade.transfer).toList()}"
    } else {
      ""
    }

  private fun threw(
    what: String,
    e: Throwable,
  ): String = "$what: threw $e at ${e.stackTrace.firstOrNull()}"

  // media3 1.11.1's limit, pinned: a partly covered texel on HLG blends in the scene light media3 holds
  // the frame in, not in display light, and the plan reports it as Degraded. Green over grey sits 1 to 2
  // codes from display light, so it cannot tell the limit from a fix, white over grey 6 to 7 and the
  // black cells 18 to 48. HdrOverlayCellsTest pins that the black cells read clear of display light.
  private fun expectedOf(
    cell: HdrOverlayCells.Cell,
    what: String,
  ): Pair<IntArray, String> =
    if (grade.transfer == HdrTransfer.Hlg && cell.isBlend) {
      tenBitCodesFromSignal(cell.hlgSceneLightSignal()) to "$what in scene light"
    } else {
      cell.codes(grade.transfer) to what
    }

  private fun miss(
    expected: IntArray,
    actual: IntArray,
    what: String,
  ): String? {
    val off = expected.indices.maxOf { abs(expected[it] - actual[it]) }
    return "$what: wrote ${actual.toList()}, expected ${expected.toList()}, $off codes off".takeIf { off > PATCH_NOISE }
  }

  private fun barMark(cell: HdrOverlayCells.Cell) =
    ImageOverlay(ImageSource.Bytes(pngOf(BADGE, cell.overlay)), Corner.TopStart, BAR_MARGIN, BAR_SCALE, cell.opacity)

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
    fill = Fill.Solid(grade.fill),
  )

  /**
   * Holds [grades], which yields one [Grade] for each fill of [HdrOverlayCells.CELLS] on a kept PQ grade and one on a
   * kept HLG grade.
   */
  companion object {
    /**
     * Every fill of [HdrOverlayCells.CELLS] on a kept PQ grade and on a kept HLG grade.
     *
     * @return One [Grade] per fill and transfer.
     */
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun grades(): List<Grade> {
      val fills = HdrOverlayCells.CELLS.map { it.background }.distinct()
      return listOf(HDR_CLIP to HdrTransfer.Pq, HLG_CLIP to HdrTransfer.Hlg).flatMap { (clip, transfer) ->
        fills.map { fill -> Grade(clip, transfer, fill) }
      }
    }

    // Small enough that, inset from the top corner, it fits inside the bar a square crop leaves.
    private const val BAR_MARGIN = 0.02f
    private const val BAR_SCALE = 0.12f
  }
}

private val SPEC = ExportSpec(targetHeight = 360, hdr = HdrMode.KeepHdr)

// The encoder's noise on a flat patch, in ten-bit codes, unmeasured on a hardware HEVC encoder. At most
// HdrOverlayCells.MAX_BLEND_TOLERANCE, so a wrong blend reads outside it on every blend cell.
internal const val PATCH_NOISE = 2

private fun Int.hex(): String = toUInt().toString(16)
