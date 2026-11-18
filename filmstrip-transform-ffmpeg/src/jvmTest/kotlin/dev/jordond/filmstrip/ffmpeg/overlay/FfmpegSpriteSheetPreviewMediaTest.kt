package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.SheetReads
import dev.jordond.filmstrip.ffmpeg.support.SheetReads.RGBA
import dev.jordond.filmstrip.ffmpeg.support.drawnRect
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.SpriteSheets
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A sprite sheet scrubbed into on the ffmpeg pump, read against the frame the shared timeline puts on each slot.
 */
class FfmpegSpriteSheetPreviewMediaTest {
  private val frames = FilterFrames()

  // The scrub goes forward first and then back, so the pump has already shown later cells when it lands on an earlier
  // one. Each landing is read for several frames, which is where a branch clock that restarted at the scrub would show.
  @Test
  fun `a seek back into a sheet shows the cells the timeline names`() =
    runTest(timeout = TIMEOUT) {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)
      val spec = SheetReads.overlayOf(sheet)
      val source = SheetReads.backdrop(RATE, CLIP_LENGTH)
      val composition: Composition =
        compositionOf {
          clip(MediaSource.of(source.absolutePath))
          effects { add(spec) }
        }
      for (rate in listOf(30, 60)) {
        val exportSpec = ExportSpec(frameRate = rate)
        val size =
          frames.engine.runs
            .lowered(composition, exportSpec, null)
            .graph
            .shouldNotBeNull()
            .invocation.output.size
        val span = TimeRange.of(Duration.ZERO, CLIP_LENGTH)
        for (at in listOf(1_700.milliseconds, 400.milliseconds, 1_300.milliseconds)) {
          val first = outputSlotOf(at, rate)
          val read =
            frames.framesAs(composition, at, SEEK_FRAMES, exportSpec) { rgba ->
              val rect = drawnRect(spec, size, span, outputSlotTime(first, rate), SpriteSheets.CELL)
              SheetReads.indexIn(SheetReads.packed(rgba, size.width, RGBA), rect, size)
            }
          read.forEachIndexed { index, shown ->
            val slot = first + index
            val expected = SheetReads.expectedFrame(spec, span, slot, rate)
            withClue("slot $slot at $rate fps after a seek to $at") { shown shouldBe expected }
          }
        }
      }
      source.delete()
    }

  private companion object {
    const val RATE = 30
    const val SEEK_FRAMES = 12
    val CLIP_LENGTH = 3.seconds
  }
}
