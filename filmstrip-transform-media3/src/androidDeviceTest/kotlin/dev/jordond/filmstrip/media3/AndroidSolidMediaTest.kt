package dev.jordond.filmstrip.media3

import android.os.Build
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HLG_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.media3.internal.bounds.gainmapLimit
import dev.jordond.filmstrip.media3.internal.hdrDrawLimit
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.assumeEncodesHdr
import dev.jordond.filmstrip.test.firstTenBitFrame
import dev.jordond.filmstrip.test.tenBitFrameCodesAt
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Solid colours exported by media3 onto a real file and read back from the first frame.
 */
class AndroidSolidMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun onlySolidsExportAtAFixedFrameInTheirColour() =
    runTest(timeout = TIMEOUT) {
      val edit =
        compositionOf {
          solid(AuthoredColors.MID_GREEN, 1.seconds)
          frame(OutputFrame.Fixed(FIXED))
        }

      val plan = probe.plan(edit, ExportSpec())
      plan.output.size shouldBe FIXED
      plan.path shouldBe ExportPath.Transcode
      assertSolid(probe.written(edit, ExportSpec()), AuthoredColors.MID_GREEN, "the solid")
    }

  @Test
  fun aLeadingSolidDrawsItsColourOverTheWholeFrameOfTheClipAfterIt() =
    runTest(timeout = TIMEOUT) {
      val edit =
        compositionOf {
          solid(AuthoredColors.MID_GREY, 1.seconds)
          clip(probe.fixture()) { trim(0.seconds, 1.seconds) }
        }

      assertSolid(probe.written(edit, ExportSpec()), AuthoredColors.MID_GREY, "the leading solid")
    }

  // A translucent solid on the primary track is composited over the fill, the same as a translucent still.
  @Test
  fun aTranslucentSolidShowsTheFillThroughIt() =
    runTest(timeout = TIMEOUT) {
      val edit =
        compositionOf {
          solid(HALF_GREY, 1.seconds)
          fill(Fill.Solid(BLACK))
          frame(OutputFrame.Fixed(FIXED))
        }

      assertSolid(probe.written(edit, ExportSpec()), QUARTER_GREY, "half grey over black")
    }

  // The solid opens the export, so the first frame is the solid on the grade the clip after it keeps. Skipped where
  // the device has no HDR encoder or cannot lay a solid on the grade.
  @Test
  fun aSolidAheadOfAnHdrClipLandsWhereTheSharedLightPutsIt() =
    runTest(timeout = TIMEOUT) {
      assumeLaysSolidsOnHdr()
      val misses =
        listOf(HDR_CLIP to HdrTransfer.Pq, HLG_CLIP to HdrTransfer.Hlg).mapNotNull { (fixture, transfer) ->
          val edit =
            compositionOf {
              solid(AuthoredColors.MID_GREEN, 1.seconds)
              clip(probe.fixture(fixture))
            }
          val read = firstTenBitFrame(probe.export(edit, HDR_LEVEL_SPEC)).codesAt(0.5f, 0.5f)
          val expected = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(AuthoredColors.MID_GREEN)))
          missOf(read, expected, "$transfer solid")
        }

      withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
    }

  @Test
  fun translucentSolidsOnAPqGradeBlendOverTheFillInLight() = runTest(timeout = TIMEOUT) { assertBlends(HdrTransfer.Pq) }

  @Test
  fun translucentSolidsOnAnHlgGradeBlendOverTheFillInLight() =
    runTest(timeout = TIMEOUT) { assertBlends(HdrTransfer.Hlg) }

  @Test
  fun translucentSolidsOnAPqGradeBlendOverABlurredCoverInLight() =
    runTest(timeout = TIMEOUT) {
      assumeLaysSolidsOnHdr()
      // Dimmed all the way, the cover is black, so each solid lands where it does over a black fill.
      val cells = HdrOverlayCells.BLENDS.filter { it.background == HdrOverlayCells.BLACK }
      val misses = blendMissesOf(HdrTransfer.Pq, cells, Fill.Blurred(dim = 1f))
      withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
    }

  private suspend fun assertBlends(transfer: HdrTransfer) {
    assumeLaysSolidsOnHdr()
    val misses =
      HdrOverlayCells.BLENDS.groupBy { it.background }.flatMap { (background, cells) ->
        blendMissesOf(transfer, cells, Fill.Solid(background))
      }
    withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
  }

  // Each blend cell is a solid of its color with its opacity as the alpha, one second each ahead of the HDR fixture.
  private suspend fun blendMissesOf(
    transfer: HdrTransfer,
    cells: List<HdrOverlayCells.Cell>,
    fill: Fill,
  ): List<String> {
    val alphas = cells.map { (it.opacity * OPAQUE_ALPHA).roundToInt() }
    val edit =
      compositionOf {
        cells.zip(alphas).forEach { (cell, alpha) -> solid((alpha shl ALPHA_SHIFT) or (cell.overlay and RGB), SLOT) }
        clip(probe.fixture(if (transfer == HdrTransfer.Pq) HDR_CLIP else HLG_CLIP))
        fill(fill)
      }
    val exported = probe.export(edit, HDR_LEVEL_SPEC)
    return cells.zip(alphas).mapIndexedNotNull { index, (cell, alpha) ->
      val laid = cell.copy(opacity = alpha / OPAQUE_ALPHA.toFloat())
      val read = tenBitFrameCodesAt(exported, SLOT * index + SLOT / 2).codesAt(0.5f, 0.5f)
      missOf(read, laid.codes(transfer), "$transfer $laid")?.plus(
        ", a signal blend lands at ${laid.signalBlendCodes(transfer).toList()}, in scene light at " +
          "${tenBitCodesFromSignal(laid.hlgSceneLightSignal()).toList()}",
      )
    }
  }

  private fun missOf(
    read: IntArray,
    expected: IntArray,
    what: String,
  ): String? =
    "$what wrote ${read.toList()}, expected ${expected.toList()}"
      .takeIf { expected.indices.any { abs(read[it] - expected[it]) > PATCH_NOISE } }

  @Test
  fun aSolidOnAnHdrGradeBelowApi34IsToneMappedByTheGainmapLimit() =
    runTest(timeout = TIMEOUT) {
      val limit = gainmapLimit(Build.VERSION.SDK_INT)
      assumeTrue("media3 lays a solid on an HDR grade on this level", limit != null)
      assumeTrue("media3 cannot tone map HDR on this device", hdrDrawLimit(Build.VERSION.SDK_INT) == null)
      val edit =
        compositionOf {
          solid(AuthoredColors.MID_GREEN, 1.seconds)
          clip(probe.fixture(HDR_CLIP))
        }

      val adjustments =
        probe.filmstrip
          .plan(edit, ExportSpec())
          .shouldBeInstanceOf<Verdict.Degraded>()
          .adjustments
      adjustments.single { it.kind == AdjustmentKind.HdrToneMapped }.message shouldStartWith checkNotNull(limit)
    }

  // media3 lays a solid on an HDR grade only from API 34, and below it the plan tone maps the grade instead.
  private suspend fun assumeLaysSolidsOnHdr() {
    probe.filmstrip.assumeEncodesHdr()
    assumeTrue("media3 lays no solid on an HDR grade on this level", gainmapLimit(Build.VERSION.SDK_INT) == null)
  }

  private fun assertSolid(
    written: Written,
    color: Int,
    what: String,
  ) {
    val expected = AuthoredColors.channelsOf(color)
    listOf(0.5f to 0.5f, CORNER to CORNER, 1f - CORNER to 1f - CORNER).forEach { (fx, fy) ->
      val read = written.rgbAt(fx, fy).toList()
      withClue("$what read $read at $fx,$fy, expected $expected") {
        read.zip(expected).all { (got, want) -> abs(got - want) <= TOLERANCE }.shouldBeTrue()
      }
    }
  }

  private companion object {
    val FIXED = Size(320, 240)
    const val CORNER = 0.02f
    val TOLERANCE = AuthoredColors.TOLERANCE.toInt()
    val HALF_GREY = 0x80808080.toInt()
    val BLACK = 0xFF000000.toInt()
    val QUARTER_GREY = 0xFF404040.toInt()
    val SLOT = 1.seconds
    const val OPAQUE_ALPHA = 255
    const val ALPHA_SHIFT = 24
    const val RGB = 0xFFFFFF
  }
}
