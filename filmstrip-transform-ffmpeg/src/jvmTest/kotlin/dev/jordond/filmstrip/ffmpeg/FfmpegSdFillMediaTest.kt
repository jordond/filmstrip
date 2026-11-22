package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration

/**
 * The fill drawn beside an SD clip, held to the colour it was given.
 *
 * A solid fill in a colour moves the plan onto BT.709, where its codes display as written. A grey one and a blurred
 * one leave the plan on its SD colour.
 */
internal class FfmpegSdFillMediaTest : SdColorTestCase() {
  @Test
  fun `a coloured fill beside an SD clip displays the colour it was given in the export and the preview`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("red-fill.mp4", TAGS_170M)
      val composition = barred(source, Fill.Solid(RED_FILL))
      val output = File(folder, "red-fill-out.mp4")
      val red = RED_FILL.signal()
      val kept = sdSourceDisplayed(red, SMPTE_C_PRIMARIES)
      withClue("the fill kept on SD codes would display ${kept.toList()}") {
        (red.indices.maxOf { abs(red[it] - kept[it]) } * MAX_CODE > SD_CODES).shouldBeTrue()
      }

      fixture.exported(fixture.plannedRun(composition, ExportSpec()), output)
      val frame = previewFrame(composition)

      tagsOf(output) shouldBe BT709_TAGS
      assertWithin(readOf(output, CENTRE, top = BAR_TOP), red, "the export's bar")
      assertWithin(frame.readAt(CENTRE, BAR_TOP), red, "the preview's bar")
      BAR_CENTRES.forEach { x ->
        val shown = sdSourceDisplayed(readOf(source, x), SMPTE_C_PRIMARIES)
        assertWithin(readOf(output, x, top = PICTURE_TOP), shown, "the export at bar $x")
        assertWithin(frame.readAt(x, PICTURE_TOP), shown, "the preview at bar $x")
      }
    }

  @Test
  fun `a grey fill beside an SD clip leaves the plan on its SD tags with codes unmoved`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("grey-fill.mp4", TAGS_170M)
      val composition = barred(source, Fill.Solid(GREY_FILL))
      val output = File(folder, "grey-fill-out.mp4")

      fixture.exported(fixture.plannedRun(composition, ExportSpec()), output)
      val frame = previewFrame(composition)

      tagsOf(output) shouldBe tagsOf(source)
      assertWithin(readOf(output, CENTRE, top = BAR_TOP), GREY_FILL.signal(), "the export's bar")
      assertWithin(frame.readAt(CENTRE, BAR_TOP), GREY_FILL.signal(), "the preview's bar")
      BAR_CENTRES.forEach { x ->
        assertWithin(readOf(output, x, top = PICTURE_TOP), readOf(source, x), "the bar at $x")
      }
    }

  // The dim scales encoded channels, which is not the same sum on SD codes as on BT.709's. Held to the BT.709 route,
  // which a BT.709 clip joined after the SD one forces.
  @Test
  fun `a dimmed blurred fill on a kept SD plan displays what the BT709 route draws`() =
    runTest(timeout = TIMEOUT) {
      val source = clip("blur-fill.mp4", TAGS_170M)
      val bt709 = clip("blur-fill-709.mp4", TAGS_709, matrix = "bt709")
      val fill = Fill.Blurred(dim = MID_DIM)
      val kept = File(folder, "blur-kept.mp4")
      val moved = File(folder, "blur-moved.mp4")

      fixture.exported(fixture.plannedRun(barred(source, fill), ExportSpec()), kept)
      fixture.exported(fixture.plannedRun(barred(source, fill, bt709), ExportSpec()), moved)

      tagsOf(kept) shouldBe tagsOf(source)
      tagsOf(moved) shouldBe BT709_TAGS
      BAR_CENTRES.forEach { x ->
        val displayed = sdSourceDisplayed(readOf(kept, x, top = BAR_TOP), SMPTE_C_PRIMARIES)
        assertWithin(displayed, readOf(moved, x, at = FIRST_CLIP, top = BAR_TOP), "the fill at $x")
      }
    }

  private fun barred(
    source: File,
    fill: Fill,
    after: File? = null,
  ): Composition =
    Composition(
      listOf(
        Track(
          listOfNotNull(source, after).map { Clip(MediaSource.of(it.absolutePath), TimeRange.of(Duration.ZERO, CLIP)) },
        ),
      ),
      fill = fill,
      frame = OutputFrame.Fixed(BARRED_FRAME),
    )

  private fun Int.signal(): DoubleArray =
    doubleArrayOf(
      (this shr RED_SHIFT and BYTE_MASK) / MAX_CODE,
      (this shr BYTE and BYTE_MASK) / MAX_CODE,
      (this and BYTE_MASK) / MAX_CODE,
    )

  private companion object {
    // Mid-range values, so a move of the primaries has room to show either way.
    const val RED_FILL = 0xFFC84632.toInt()
    const val GREY_FILL = 0xFF808080.toInt()
    const val MID_DIM = 0.5f
    const val CENTRE = 0.5
    const val RED_SHIFT = 16
    const val BYTE = 8
  }
}
