package dev.jordond.filmstrip.ffmpeg.hdr

import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_HDR_SPEC
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.ExportedOverlay
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.pointIn
import dev.jordond.filmstrip.ffmpeg.support.tenBitCodesAt
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.HdrOverlayCells
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Translucent solids ahead of an HDR clip on a kept grade, read back as ten-bit codes against where a blend in display
 * light puts them.
 *
 * Each blend cell of [HdrOverlayCells] is laid as a solid of the cell's color with its opacity as the alpha, over a
 * fill of the cell's background, and read against [HdrOverlayCells.Cell.codes].
 */
class FfmpegHdrSourceAlphaMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `translucent solids on a PQ grade blend over the fill in light`() =
    runTest(timeout = TIMEOUT) { assertBlends(HdrTransfer.Pq) }

  @Test
  fun `translucent solids on an HLG grade blend over the fill in light`() =
    runTest(timeout = TIMEOUT) { assertBlends(HdrTransfer.Hlg) }

  @Test
  fun `translucent solids on a PQ grade blend over a blurred cover in light`() =
    runTest(timeout = TIMEOUT) {
      // Dimmed all the way, the cover is black, so each solid lands where it does over a black fill.
      val cells = HdrOverlayCells.BLENDS.filter { it.background == HdrOverlayCells.BLACK }
      val misses = missesOf(HdrTransfer.Pq, cells, Fill.Blurred(dim = 1f))
      withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
    }

  private suspend fun assertBlends(transfer: HdrTransfer) {
    val misses =
      HdrOverlayCells.BLENDS.groupBy { it.background }.flatMap { (background, cells) ->
        missesOf(transfer, cells, Fill.Solid(background))
      }
    withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
  }

  // The solids play one slot each in the order given, ahead of a slot of the HDR fixture that keeps the grade.
  private suspend fun missesOf(
    transfer: HdrTransfer,
    cells: List<HdrOverlayCells.Cell>,
    fill: Fill,
  ): List<String> {
    val alphas = cells.map { (it.opacity * OPAQUE_ALPHA).roundToInt() }
    val exported = exported(transfer, fill, cells.zip(alphas) { cell, alpha -> (alpha shl ALPHA_SHIFT) or cell.rgb })
    val centre = pointIn(HALF, HALF, exported.plan.output.size)
    val misses =
      cells.zip(alphas).mapIndexedNotNull { index, (cell, alpha) ->
        val laid = cell.copy(opacity = alpha / OPAQUE_ALPHA.toFloat())
        val read = tenBitCodesAt(exported.file, centre, SLOT * index + SLOT / 2)
        val expected = laid.codes(transfer)
        val landed = read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE }
        if (landed) return@mapIndexedNotNull null
        "$transfer $laid read ${read.toList()}, expected ${expected.toList()}, a signal blend lands at " +
          "${laid.signalBlendCodes(transfer).toList()} and an sRGB blend at ${laid.srgbBlendCodes(transfer).toList()}"
      }
    exported.file.delete()
    return misses
  }

  private suspend fun exported(
    transfer: HdrTransfer,
    fill: Fill,
    solids: List<Int>,
  ): ExportedOverlay {
    val source = if (transfer == HdrTransfer.Pq) fixture.hdr else fixture.hdrHlg
    val output = File.createTempFile("filmstrip-hdr-source-alpha", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        solids.forEach { solid(it, SLOT) }
        clip(MediaSource.of(source.absolutePath)) { trim(Duration.ZERO, SLOT) }
        fill(fill)
        frame(OutputFrame.Fixed(FRAME))
      }
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, COLOR_READ_HDR_SPEC)) {
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
    finished.shouldBeInstanceOf<ExportStatus.Success>()
    return ExportedOverlay(output, plan)
  }

  private val HdrOverlayCells.Cell.rgb: Int
    get() = overlay and RGB

  private companion object {
    val SLOT = 200.milliseconds
    val FRAME = Size(320, 180)
    const val OPAQUE_ALPHA = 255
    const val ALPHA_SHIFT = 24
    const val RGB = 0xFFFFFF
  }
}
