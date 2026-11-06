package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.CompositionBuilder
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.effects.geometry.turnedFrame
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Exports whose clips place themselves through a letterboxing or stretching crop, read back from the written file.
 *
 * The fill is a color the fixture's bars never show, so a bar cannot be mistaken for the picture.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleClipPlacementTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a clip's own letterboxing crop writes the fill into its canvas bars`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(AspectRatio.Portrait, Fit.Contain)
      val (path, plan) =
        exported("letterbox") {
          clip(MediaSource.of(fixture(LANDSCAPE))) {
            trim(0.seconds, 1.seconds)
            effects { add(crop) }
          }
          fill(Fill.Solid(SLATE.toArgb()))
        }
      val content = placementOf(Size(640, 360), listOf(listOf(crop)), plan.output.size).drawnRect

      isSlate(samplePixel(path, BAR_CENTRES[1], content.top / 2)).shouldBeTrue()
      isSlate(samplePixel(path, BAR_CENTRES[1], (1 + content.bottom) / 2)).shouldBeTrue()
      isSlate(samplePixel(path, BAR_CENTRES[1], (content.top + content.bottom) / 2)).shouldBeFalse()
      remove(path)
    }

  // A preview lowers the same edit at a smaller height, and the placement is measured against the natural frame, so
  // the content lands on the same fraction of the frame at both heights.
  @Test
  fun `a letterboxing crop places its content on the same rect at a preview height`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(AspectRatio.Portrait, Fit.Contain)
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture(LANDSCAPE))) {
            trim(0.seconds, 1.seconds)
            effects { add(crop) }
          }
          fill(Fill.Solid(SLATE.toArgb()))
        }
      val (exportPath, exportPlan) = exported(composition, temporaryPath("letterbox-export"))
      val content = placementOf(Size(640, 360), listOf(listOf(crop)), exportPlan.output.size).drawnRect
      val (previewPath, previewPlan) =
        exported(
          composition,
          temporaryPath("letterbox-preview"),
          ExportSpec(heightPx = PREVIEW_HEIGHT, bitrate = COLOR_READ_BITRATE),
        )
      withClue("the preview has to be lowered smaller than the export for this to tell") {
        (previewPlan.output.size.height < exportPlan.output.size.height).shouldBeTrue()
      }

      listOf(exportPath to exportPlan.output.size, previewPath to previewPlan.output.size).forEach { (path, size) ->
        val frame = frameOf(path)
        val x = BAR_CENTRES[1]
        withClue("${size.height} rows, the bar above") { isSlate(frame.at(x, content.top - EDGE)).shouldBeTrue() }
        withClue("${size.height} rows, the content's top") { isSlate(frame.at(x, content.top + EDGE)).shouldBeFalse() }
        withClue("${size.height} rows, the content's bottom") {
          isSlate(frame.at(x, content.bottom - EDGE)).shouldBeFalse()
        }
        withClue("${size.height} rows, the bar below") { isSlate(frame.at(x, content.bottom + EDGE)).shouldBeTrue() }
        remove(path)
      }
    }

  @Test
  fun `a stretching crop fills the frame and leaves no bar`() =
    runTest(timeout = TIMEOUT) {
      val (path, _) =
        exported("stretch") {
          clip(MediaSource.of(fixture(LANDSCAPE))) {
            trim(0.seconds, 1.seconds)
            effects { crop(AspectRatio.Portrait, fit = Fit.Stretch) }
          }
          fill(Fill.Solid(SLATE.toArgb()))
        }

      isSlate(samplePixel(path, BAR_CENTRES[1], EDGE)).shouldBeFalse()
      isSlate(samplePixel(path, BAR_CENTRES[1], 1 - EDGE)).shouldBeFalse()
      remove(path)
    }

  // The composition's crop reads the square canvas the clip's crop made, bars included. Cropping the content alone
  // would leave a frame with no bar in it at all.
  @Test
  fun `a crop after a letterboxing crop cuts the canvas bars included`() =
    runTest(timeout = TIMEOUT) {
      val (path, _) =
        exported("canvas") {
          clip(MediaSource.of(fixture(LANDSCAPE))) {
            trim(0.seconds, 1.seconds)
            effects { crop(AspectRatio.Square, fit = Fit.Contain) }
          }
          effects { crop(NormalizedRect(0f, 0f, 0.5f, 1f)) }
          fill(Fill.Solid(SLATE.toArgb()))
        }

      val top = samplePixel(path, 0.5f, EDGE)
      withClue("the top of the canvas read $top") { isSlate(top).shouldBeTrue() }
      isSlate(samplePixel(path, 0.5f, 0.5f)).shouldBeFalse()
      remove(path)
    }

  // An overlay reads the canvas, so one in the corner is drawn on the square canvas, up in its top bar, rather than on
  // the content below it. A pan between the crop and the overlay moves the canvas under it but not the overlay.
  @Test
  fun `a clip overlay after a letterboxing crop lands on the canvas with or without a pan`() =
    runTest(timeout = TIMEOUT) {
      listOf(listOf(SQUARE, MARK), listOf(SQUARE, PAN, MARK)).forEachIndexed { index, chain ->
        val (path, plan) =
          exported("overlay-$index") {
            clip(MediaSource.of(fixture(LANDSCAPE))) {
              trim(0.seconds, 1.seconds)
              effects { chain.forEach { add(it) } }
            }
            fill(Fill.Solid(SLATE.toArgb()))
          }
        val size = plan.output.size
        val marked = MARK.placedOn(size, MARK_PICTURE).rectOn(size)
        val content = placementOf(Size(640, 360), listOf(listOf(SQUARE)), size).drawnRect
        withClue("the overlay has to sit in the canvas's top bar for this to tell") {
          (marked.bottom < content.top).shouldBeTrue()
        }
        val x = (marked.left + marked.right) / 2
        val y = (marked.top + marked.bottom) / 2

        assertClose(frameOf(path).average(x, y), MARK_RGB, "the overlay at ($x, $y) after ${chain.size - 1} effects")
        remove(path)
      }
    }

  // The composition's square crop is measured on the frame the clip's turn leaves, which is portrait. Measured on the
  // landscape source instead it would keep a strip of the turned frame and stretch it square.
  @Test
  fun `a composition crop after a clip turn keeps a square of the turned frame`() =
    runTest(timeout = TIMEOUT) {
      val plain = reference()
      val crop = Crop(AspectRatio.Square, Fit.Crop)
      val (path, _) =
        exported("turned-square") {
          clip(MediaSource.of(fixture(LANDSCAPE))) {
            trim(0.seconds, 1.seconds)
            effects { add(Rotate(QUARTER_TURN)) }
          }
          effects { add(crop) }
        }
      val kept = crop.retainedRect(Rotate(QUARTER_TURN).turnedFrame(Size(plain.width, plain.height)))

      // A counter-clockwise quarter turn takes a point of the turned frame back to (1 - y, x) of the source.
      assertDrawn(frameOf(path), plain, GRID) { x, y ->
        (1f - (kept.top + y * kept.height)) to (kept.left + x * kept.width)
      }
      remove(path)
    }

  // The composition's crop reads the square canvas the clip's crop made, so it is measured on that canvas and keeps
  // the middle four fifths of it, bars included, rather than a strip of the source's width.
  @Test
  fun `a composition crop after a letterboxing crop is measured on the canvas`() =
    runTest(timeout = TIMEOUT) {
      val plain = reference()
      val crop = Crop(AspectRatio(4, 5), Fit.Crop)
      val (path, _) =
        exported("canvas-measured") {
          clip(MediaSource.of(fixture(LANDSCAPE))) {
            trim(0.seconds, 1.seconds)
            effects { add(SQUARE) }
          }
          effects { add(crop) }
          fill(Fill.Solid(SLATE.toArgb()))
        }
      val source = Size(plain.width, plain.height)
      val canvas = placementOf(source, listOf(listOf(SQUARE), listOf(crop)), source).canvases.single()
      val kept = crop.retainedRect(canvas.size)

      assertDrawn(frameOf(path), plain, GRID) { x, y ->
        canvas.contentInCanvas.contentPointOf(kept.left + x * kept.width, kept.top + y * kept.height)
      }
      remove(path)
    }

  // The composition's turn reads the clip's square canvas, and its crop then makes a portrait canvas around the turned
  // square, so the content keeps the source's aspect turned on its side rather than stretching to either canvas.
  @Test
  fun `a canvas turned and cropped again keeps the content's aspect`() =
    runTest(timeout = TIMEOUT) {
      val plain = reference()
      val tall = Crop(AspectRatio(9, 16), Fit.Contain)
      val (path, plan) =
        exported("nested") {
          clip(MediaSource.of(fixture(LANDSCAPE))) {
            trim(0.seconds, 1.seconds)
            effects { add(SQUARE) }
          }
          effects {
            add(Rotate(QUARTER_TURN))
            add(tall)
          }
          fill(Fill.Solid(SLATE.toArgb()))
        }
      val source = Size(plain.width, plain.height)
      val output = plan.output.size
      val placement = placementOf(source, listOf(listOf(SQUARE), listOf(Rotate(QUARTER_TURN), tall)), output)
      val square = placement.drawnRect
      val content = placement.canvases.single().contentInCanvas
      // Turned, the content's height on the canvas runs across the frame.
      val aspect = content.height * square.width * output.width / (content.width * square.height * output.height)
      withClue("the turned content is ${source.height} by ${source.width}, and the placement made it $aspect") {
        abs(aspect - source.height.toFloat() / source.width) shouldBeLessThan ASPECT_SLACK
      }

      assertDrawn(frameOf(path), plain, GRID) { x, y ->
        val u = (x - square.left) / square.width
        val v = (y - square.top) / square.height
        if (u !in 0f..1f || v !in 0f..1f) null else content.contentPointOf(1f - v, u)
      }
      remove(path)
    }

  // The plain export of the fixture, so every expected color has been through the same pipeline as the one it is
  // compared with.
  private suspend fun reference(): FrameProbe {
    val (path, _) = exported("plain") { clip(MediaSource.of(fixture(LANDSCAPE))) { trim(0.seconds, 1.seconds) } }
    return frameOf(path).also { remove(path) }
  }

  // Reads every point of [probes] in [drawn] against [plain] at the source point [toSource] takes it to, or against the
  // fill where it takes it to none. A point near an edge of the picture, or near an edge in the source, reads a blend,
  // so it is left out. The reads have to reach the content and the fill both or a placement that moved everything to
  // one side would pass.
  private fun assertDrawn(
    drawn: FrameProbe,
    plain: FrameProbe,
    probes: List<Pair<Float, Float>>,
    toSource: (Float, Float) -> Pair<Float, Float>?,
  ) {
    val reads =
      probes.mapNotNull { (x, y) ->
        val point = toSource(x, y)
        val read = drawn.at(x, y)
        val nearEdge = NEIGHBOURS.any { (dx, dy) -> (toSource(x + dx, y + dy) == null) != (point == null) }
        when {
          nearEdge -> {
            null
          }
          point == null -> {
            withClue("($x, $y) read $read where the placement puts a bar") { isSlate(read).shouldBeTrue() }
            false
          }
          plain.isFlatAt(point.first, point.second) -> {
            assertClose(read, plain.at(point.first, point.second), "($x, $y) from source $point")
            true
          }
          else -> {
            null
          }
        }
      }
    reads.shouldContain(true)
    if (probes.any { (x, y) -> toSource(x, y) == null }) reads.shouldContain(false)
  }

  private fun FrameProbe.isFlatAt(
    x: Float,
    y: Float,
  ): Boolean = NEIGHBOURS.all { (dx, dy) -> distance(at(x + dx, y + dy), at(x, y)) <= COLOR_TOLERANCE }

  // Where a canvas point lands on the content this rect places in the canvas, or null when it lands on a bar.
  private fun NormalizedRect.contentPointOf(
    x: Float,
    y: Float,
  ): Pair<Float, Float>? {
    val contentX = (x - left) / width
    val contentY = (y - top) / height
    return if (contentX in 0f..1f && contentY in 0f..1f) contentX to contentY else null
  }

  private suspend fun exported(
    name: String,
    build: CompositionBuilder.() -> Unit,
  ): Pair<String, ExportPlan> = exported(compositionOf(build), temporaryPath(name))

  private suspend fun exported(
    composition: EditComposition,
    output: String,
    spec: ExportSpec = SPEC,
  ): Pair<String, ExportPlan> =
    withContext(Dispatchers.Default) {
      val plan =
        when (val verdict = filmstrip.plan(composition, spec)) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> verdict.plan
          is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
        }
      val finished = filmstrip.export(plan, MediaSink.of(output)).toList().last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()
      output to plan
    }

  private fun isSlate(pixel: Triple<Int, Int, Int>): Boolean = distance(pixel, SLATE) <= COLOR_TOLERANCE

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-placement-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    const val LANDSCAPE = "apple_export_a.mp4"
    val TIMEOUT = 2.seconds * 60
    val SPEC = ExportSpec(heightPx = 360, bitrate = COLOR_READ_BITRATE)

    // A height a preview lowers the edit at, under the export's own.
    const val PREVIEW_HEIGHT = 180
    val SLATE = Triple(0x20, 0x40, 0x60)

    // Read two percent in from an edge, clear of the encoder's own edge blocks.
    const val EDGE = 0.02f
    const val QUARTER_TURN = 90
    val SQUARE = Crop(AspectRatio.Square, Fit.Contain)
    val PAN = KenBurns(NormalizedRect(0.05f, 0.1f, 0.85f, 0.9f), NormalizedRect(0.05f, 0.1f, 0.85f, 0.9f))

    // A small flat overlay in a color neither the fixture's bars nor the fill carry, set in so it sits wholly inside a
    // square canvas's top bar.
    val MARK_RGB = Triple(0xE6, 0x78, 0x14)
    val MARK_PICTURE = Size(16, 16)
    val MARK =
      ImageOverlay(
        ImageSource.ofBytes(OverlayPixels.pngOf(MARK_PICTURE, MARK_RGB.toArgb())),
        Corner.TopStart,
        margin = 0.02f,
        scale = 0.15f,
      )

    // A grid off the middle of every axis, so a placement right only at the centre or the edges still misses.
    val GRID =
      listOf(0.08f, 0.2f, 0.33f, 0.45f, 0.58f, 0.7f, 0.83f, 0.94f).let { at ->
        at.flatMap { x ->
          at.map {
            x to
              it
          }
        }
      }

    // How far around a point a read has to match it, on every side, for the point to sit clear of an edge.
    const val FLAT = 0.02f
    val NEIGHBOURS = listOf(-FLAT to 0f, FLAT to 0f, 0f to -FLAT, 0f to FLAT)

    // A placement read off a whole-pixel frame lands within this of the aspect it was asked for.
    const val ASPECT_SLACK = 0.02f
  }
}
