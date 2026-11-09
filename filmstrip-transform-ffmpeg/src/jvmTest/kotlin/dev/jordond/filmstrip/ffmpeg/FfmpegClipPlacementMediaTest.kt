package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_BITRATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_HDR_SPEC
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.RED
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_COLOR
import dev.jordond.filmstrip.ffmpeg.support.assertReads
import dev.jordond.filmstrip.ffmpeg.support.centreOf
import dev.jordond.filmstrip.ffmpeg.support.channelsOf
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.hostFfprobe
import dev.jordond.filmstrip.ffmpeg.support.pixelAt
import dev.jordond.filmstrip.ffmpeg.support.writtenFrames
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.geometry.inWholePixelsOf
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Exports whose clips place themselves through a letterboxing or stretching crop, read back from the written file.
 */
class FfmpegClipPlacementMediaTest {
  private val fixture = FfmpegExportFixture()

  // A clip's own crop sizes the output, so before every clip carried its own placement the bars read as covered and
  // the fill was dropped as inert.
  @Test
  fun `a clip's own letterboxing crop writes the fill into its canvas bars`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val (path, plan) =
        exported(
          compositionOf {
            clip(MediaSource.of(fixture.landscape.absolutePath)) {
              effects { crop(AspectRatio.Portrait, fit = Fit.Contain) }
            }
            fill(Fill.Solid(RED))
          },
        )

      val size = plan.output.size
      isReddish(pixelAt(path, size.width / 2, 2)).shouldBeTrue()
      isReddish(pixelAt(path, size.width / 2, size.height - 2)).shouldBeTrue()
    }

  // A preview lowers the same edit at a smaller height, and the placement is measured against the natural frame, so
  // the content lands on the same fraction of the frame at both heights.
  @Test
  fun `a letterboxing crop places its content on the same rect at a preview height`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val crop = Crop(AspectRatio.Portrait, Fit.Contain)
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture.landscape.absolutePath)) { effects { add(crop) } }
          fill(Fill.Solid(SLATE))
        }
      val (exportPath, exportPlan) = exported(composition, ExportSpec(bitrate = COLOR_READ_BITRATE))
      val content = placementOf(landscapeSize(), listOf(listOf(crop)), exportPlan.output.size).drawnRect
      val (previewPath, previewPlan) =
        exported(composition, ExportSpec(heightPx = exportPlan.output.size.height / 2, bitrate = COLOR_READ_BITRATE))
      withClue("the preview has to be lowered smaller than the export for this to tell") {
        (previewPlan.output.size.height < exportPlan.output.size.height).shouldBeTrue()
      }

      listOf(exportPath to exportPlan.output.size, previewPath to previewPlan.output.size).forEach { (path, size) ->
        fun read(y: Float) = pixelAt(path, size.width / 2, (y * size.height).toInt())

        withClue("${size.height} rows, the bar above the content") { isSlate(read(content.top - EDGE)).shouldBeTrue() }
        withClue("${size.height} rows, the content's top") { isSlate(read(content.top + EDGE)).shouldBeFalse() }
        withClue("${size.height} rows, the content's bottom") { isSlate(read(content.bottom - EDGE)).shouldBeFalse() }
        withClue("${size.height} rows, the bar below the content") {
          isSlate(read(content.bottom + EDGE)).shouldBeTrue()
        }
      }
    }

  @Test
  fun `a stretching crop fills the frame and leaves no bar`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val (path, plan) =
        exported(
          compositionOf {
            clip(MediaSource.of(fixture.landscape.absolutePath)) {
              effects { crop(AspectRatio.Portrait, fit = Fit.Stretch) }
            }
            fill(Fill.Solid(RED))
          },
        )

      val size = plan.output.size
      isReddish(pixelAt(path, size.width / 2, 2)).shouldBeFalse()
      isReddish(pixelAt(path, size.width / 2, size.height - 2)).shouldBeFalse()
    }

  // The composition's crop reads the square canvas the clip's crop made, bars included. Cropping the content alone
  // would leave a frame with no bar in it at all. The fill is a color the source never shows, so a bar cannot be
  // mistaken for the picture.
  @Test
  fun `a crop after a letterboxing crop cuts the canvas bars included`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val (path, plan) =
        exported(
          compositionOf {
            clip(MediaSource.of(fixture.landscape.absolutePath)) {
              effects { crop(AspectRatio.Square, fit = Fit.Contain) }
            }
            effects { crop(NormalizedRect(0f, 0f, 0.5f, 1f)) }
            fill(Fill.Solid(SLATE))
          },
        )

      val size = plan.output.size
      val top = pixelAt(path, size.width / 2, 2)
      withClue("the top of the canvas read $top") { isSlate(top).shouldBeTrue() }
      isSlate(pixelAt(path, size.width / 2, size.height / 2)).shouldBeFalse()
    }

  // The canvas a 1366 by 767 frame makes under a 4:5 crop is 767 rows tall. Built on the 4:2:0 grid it would be a row
  // short and its content a row high. The band cut out of it straddles the content's top edge and is written at its own
  // 42 rows, so a row off moves the edge a row in the written frame.
  @Test
  fun `a canvas of odd height places its content on the row the planner measured`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      assertOddCanvasEdge(ExportSpec(bitrate = COLOR_READ_BITRATE), hdr = false)
    }

  // The same canvas on a kept PQ grade is built in ten bit 4:4:4 and written back out in ten bits.
  @Test
  fun `a canvas of odd height on a kept HDR grade places its content on the row the planner measured`() =
    runTest(timeout = TIMEOUT) {
      assertOddCanvasEdge(COLOR_READ_HDR_SPEC, hdr = true)
    }

  private suspend fun assertOddCanvasEdge(
    spec: ExportSpec,
    hdr: Boolean,
  ) {
    val source = File.createTempFile("filmstrip-odd-height", ".mkv").also { it.delete() }
    try {
      writeFlat(source, ODD_SOURCE, hdr)
      val (path, plan) =
        exported(
          compositionOf {
            clip(MediaSource.of(source.absolutePath)) {
              effects {
                add(PORTRAIT_CANVAS)
                add(EDGE_BAND)
              }
            }
            fill(Fill.Solid(SLATE))
          },
          spec,
        )
      if (hdr) {
        withClue("the kept grade has to be written in ten bits") { (pixelFormatOf(path).contains("10")).shouldBeTrue() }
      }
      val size = plan.output.size
      val placement = placementOf(ODD_SOURCE, listOf(listOf(PORTRAIT_CANVAS, EDGE_BAND)), size)
      val canvas = placement.canvases.single()
      canvas.size.height shouldBe ODD_SOURCE.height
      val contentTop = canvas.contentInCanvas.inWholePixelsOf(canvas.size).top
      val drawn = placement.drawnRect
      val expected =
        (drawn.top + (contentTop - BAND_TOP).toFloat() / BAND_ROWS * drawn.height) * size.height

      val frame = fixture.frameAt(path, Duration.ZERO)
      val column = size.width / 2
      val green = List(size.height) { row -> frame[(row * size.width + column) * RGB + 1].toInt() and 0xFF }
      // The bar fills the band's top row and the content its bottom one, whatever light the grade writes either in.
      val midpoint = (green.first() + green.last()) / 2
      withClue("the content reads no brighter than the bar, ${green.first()} against ${green.last()}") {
        (green.last() - green.first() > DRIFT).shouldBeTrue()
      }
      val crossing = green.indexOfFirst { it >= midpoint }
      // Under a whole row, so an edge a row off fails. Every encoder here crosses on the row itself.
      withClue("the content's top edge crossed at row $crossing, expected $expected") {
        (abs(crossing - expected) < 1f).shouldBeTrue()
      }
    } finally {
      source.delete()
    }
  }

  // An overlay is a canvas reader, so the clip's square canvas is built ahead of it and the corner it is anchored to is
  // the canvas's own, up in the bar. Drawn on the content instead, it would land below the bar.
  @Test
  fun `a clip overlay after a letterboxing crop lands on the canvas`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val overlay = fixture.watermark(image = BANNER)
      val (path, plan) =
        exported(
          compositionOf {
            clip(MediaSource.of(fixture.landscape.absolutePath)) {
              effects {
                add(SQUARE_CANVAS)
                add(overlay)
              }
            }
            fill(Fill.Solid(SLATE))
          },
        )
      val size = plan.output.size
      val marked = overlay.placedOn(size, BANNER).rectOn(size)
      val content = placementOf(landscapeSize(), listOf(listOf(SQUARE_CANVAS)), size).drawnRect
      withClue("the overlay has to sit in the canvas's top bar for this to tell") {
        (marked.bottom < content.top).shouldBeTrue()
      }

      assertReads(writtenFrames(path, probe = centreOf(marked, size)), channelsOf(OVERLAY_COLOR), "the overlay")
    }

  // The composition's turn reads the clip's square canvas and its crop then makes a portrait canvas around the turned
  // square, so the content keeps the source's aspect turned on its side and is never stretched to either canvas.
  @Test
  fun `a canvas turned and cropped again keeps the content's aspect`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val (path, plan) =
        exported(
          compositionOf {
            clip(MediaSource.of(fixture.landscape.absolutePath)) { effects { add(SQUARE_CANVAS) } }
            effects {
              rotate(QUARTER_TURN)
              crop(AspectRatio(9, 16), fit = Fit.Contain)
            }
            fill(Fill.Solid(SLATE))
          },
        )
      val size = plan.output.size
      val stages = listOf(listOf(SQUARE_CANVAS), listOf(Rotate(QUARTER_TURN), Crop(AspectRatio(9, 16), Fit.Contain)))
      val placement = placementOf(landscapeSize(), stages, size)
      val square = placement.drawnRect
      // A quarter turn counter-clockwise lays the canvas's top bar down its left side.
      val content = placement.canvases.single().contentInCanvas
      val left = square.left + content.top * square.width
      val right = square.left + content.bottom * square.width
      val middle = (square.top + square.bottom) / 2
      val source = landscapeSize()
      val aspect = (right - left) * size.width / (square.height * size.height)
      withClue("the turned content is ${source.height} by ${source.width}") {
        aspect shouldBe (source.height.toFloat() / source.width plusOrMinus ASPECT_SLACK)
      }

      fun read(
        x: Float,
        y: Float,
      ) = pixelAt(path, (x * size.width).toInt(), (y * size.height).toInt())

      withClue("the left canvas bar") { isSlate(read((square.left + left) / 2, middle)).shouldBeTrue() }
      withClue("the right canvas bar") { isSlate(read((right + square.right) / 2, middle)).shouldBeTrue() }
      withClue("the bar above the square") { isSlate(read(0.5f, square.top / 2)).shouldBeTrue() }
      withClue("the content") { isSlate(read((left + right) / 2, middle)).shouldBeFalse() }
      withClue("the content's top edge") { isSlate(read((left + right) / 2, square.top + EDGE)).shouldBeFalse() }
    }

  // The brightness runs on the clip's canvas, after its crop, so it would darken the canvas bars too if they were
  // painted before it. They stay clear until the fill is painted in, and read as the fill as it was written.
  @Test
  fun `a grade after a canvas leaves the canvas bars to the ungraded fill`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val (path, plan) =
        exported(
          compositionOf {
            clip(MediaSource.of(fixture.landscape.absolutePath)) {
              effects {
                add(SQUARE_CANVAS)
                brightness(HALF)
              }
            }
            effects { rotate(QUARTER_TURN) }
            fill(Fill.Solid(RED))
          },
        )
      val size = plan.output.size
      val placement = placementOf(landscapeSize(), listOf(listOf(SQUARE_CANVAS), listOf(Rotate(QUARTER_TURN))), size)
      // Turned, the canvas's top bar runs down the frame's left side.
      val bar =
        placement.canvases
          .single()
          .contentInCanvas.top / 2

      val read = pixelAt(path, (bar * size.width).toInt(), size.height / 2)
      withClue("the left canvas bar read $read") { isReddish(read).shouldBeTrue() }
    }

  private suspend fun exported(
    composition: Composition,
    spec: ExportSpec = ExportSpec(),
  ): Pair<String, ExportPlan> {
    val output = File.createTempFile("filmstrip-placement", ".mp4").also { it.deleteOnExit() }
    output.delete()
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, spec)) {
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
    return output.absolutePath to plan
  }

  // A second of one flat color at size. FFV1 is lossless and takes an odd height, which no 4:2:0 H264 encode does. An
  // hdr one is written in ten bits and tagged PQ BT.2020, so the planner keeps its grade.
  private fun writeFlat(
    file: File,
    size: Size,
    hdr: Boolean,
  ) {
    val color = "%06X".format(FLAT_GREEN and 0xFFFFFF)
    val format =
      if (hdr) {
        "yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv"
      } else {
        "yuv420p"
      }
    val graph = "color=c=0x$color:s=${size.width}x${size.height}:r=30:d=1,format=$format"
    val command =
      listOf(hostFfmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", graph) +
        listOf("-c:v", "ffv1", file.absolutePath)
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val log = process.inputStream.readAllBytes().decodeToString()
    withClue("ffmpeg could not write the flat clip: $log") {
      (process.waitFor() == 0 && file.isFile).shouldBeTrue()
    }
  }

  private fun pixelFormatOf(path: String): String {
    val command =
      listOf(hostFfprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=pix_fmt") +
        listOf("-of", "csv=p=0", path)
    val process = ProcessBuilder(command).start()
    val format = process.inputReader().readText().trim()
    process.waitFor()
    return format
  }

  private fun isSlate(pixel: Triple<Int, Int, Int>): Boolean {
    val (red, green, blue) = pixel
    return abs(red - 0x20) < DRIFT && abs(green - 0x40) < DRIFT && abs(blue - 0x60) < DRIFT
  }

  // A flat red region survives H264 with only rounding at the YUV boundary, never a shift toward another color.
  private fun isReddish(pixel: Triple<Int, Int, Int>): Boolean {
    val (red, green, blue) = pixel
    return red > 200 && green < 60 && blue < 60
  }

  private suspend fun landscapeSize(): Size =
    fixture.filmstrip
      .probe(MediaSource.of(fixture.landscape.absolutePath))
      .shouldBeInstanceOf<ProbeResult.Success>()
      .info.video
      .shouldNotBeNull()
      .displaySize

  private companion object {
    const val SLATE = 0xFF204060.toInt()
    const val DRIFT = 24
    const val QUARTER_TURN = 90
    const val HALF = 0.5f

    // How far inside the square the content's edge probe sits, clear of the encoder's blur across the edge.
    const val EDGE = 0.03f

    // A whole-pixel output frame moves an aspect read off it by under a hundredth.
    const val ASPECT_SLACK = 0.01f
    val SQUARE_CANVAS = Crop(AspectRatio.Square, Fit.Contain)
    val PORTRAIT_CANVAS = Crop(AspectRatio(4, 5), Fit.Contain)

    // A frame of odd height, so a 4:5 canvas around it is odd too.
    val ODD_SOURCE = Size(1366, 767)
    const val FLAT_GREEN = 0xFF30C030.toInt()
    const val RGB = 3

    // A square band of the 614 by 767 canvas from row 190, 42 rows tall, around the content's top edge at row 211.
    // The rect runs half a pixel past whole rows and columns, so the crop's own rounding down lands on these.
    const val BAND_TOP = 190
    const val BAND_ROWS = 42
    val EDGE_BAND =
      CropRect(NormalizedRect(0.25f, 190.5f / 767f, 0.25f + 42.5f / 614f, 233f / 767f))

    // A short overlay, so one in a corner sits wholly inside a canvas bar.
    val BANNER = Size(64, 16)
  }
}
