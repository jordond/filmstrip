package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.SheetReads
import dev.jordond.filmstrip.ffmpeg.support.drawnRect
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.SpriteSheets
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A sprite sheet drawn on a kept HDR grade, with the cell on every written frame read off the ten bit luma plane.
 */
class FfmpegSpriteSheetHdrMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a sheet on a kept PQ grade shows the timeline's frame on every slot`() =
    runTest(timeout = TIMEOUT) { assertHdrExport(fixture.hdr) }

  @Test
  fun `a sheet on a kept HLG grade shows the timeline's frame on every slot`() =
    runTest(timeout = TIMEOUT) { assertHdrExport(fixture.hdrHlg) }

  private suspend fun assertHdrExport(source: File) {
    val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)
    val spec = SheetReads.overlayOf(sheet)
    val composition =
      compositionOf {
        clip(MediaSource.of(source.absolutePath)) { trim(Duration.ZERO..LENGTH) }
        effects { add(spec) }
      }
    val exportSpec = ExportSpec(frameRate = RATE, hdr = HdrMode.KeepHdr)
    val plan = fixture.plannedRun(composition, exportSpec)
    val output = File.createTempFile("filmstrip-sheet-hdr", ".mp4").also { it.delete() }
    fixture.exported(plan, output)
    val size = plan.output.size
    val span = TimeRange.of(Duration.ZERO, plan.duration)

    val count =
      SheetReads.forEachLuma(output.absolutePath, size, bytesPerSample = 2) { slot, luma ->
        val texels = { x: Int, y: Int ->
          val at = (y * size.width + x) * 2
          val code = (luma[at].toInt() and 0xFF) or ((luma[at + 1].toInt() and 0xFF) shl 8)
          if (code > WHITE_FLOOR) WHITE else BLACK
        }
        val rect = drawnRect(spec, size, span, outputSlotTime(slot.toLong(), RATE), SpriteSheets.CELL)
        val expected = SheetReads.expectedFrame(spec, span, slot.toLong(), RATE)
        withClue("slot $slot of ${source.name}") { SheetReads.indexIn(texels, rect, size) shouldBe expected }
      }
    count shouldBeGreaterThan (RATE * LENGTH.inWholeSeconds).toInt() / 2
    output.delete()
  }

  private companion object {
    const val RATE = 30
    val LENGTH = 3.seconds

    // Black sits at ten bit code 64 and an overlay white well above 300 on either transfer.
    const val WHITE_FLOOR = 300
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
  }
}
