package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.CODE_PATCH
import dev.jordond.filmstrip.test.writeAtomically
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import dev.jordond.filmstrip.transform.internal.geometry.coverScale
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The bars a real media3 export draws for a [Fill.Blurred], read against [blurPlane] run by the fill's shared plan on
 * the cover crop of the clip.
 *
 * A square canvas over a landscape picture leaves a bar above and below it, and the cover crops the picture's sides.
 * The picture changes only across its width: a light strip from just inside the left crop line to an edge, mid grey
 * after it, and dark everywhere the crop cuts. A blur held at the crop's edge reads apart from one that takes in the
 * dark beyond it. The levels come off a plain export of the same picture, so the reads allow only for what an encoder
 * moves.
 */
class AndroidFillBlurMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aBlurredFillLandsOnTheSharedPlanMidFrameAndAtTheCoverCropEdge() =
    runTest(timeout = TIMEOUT) {
      val source = picture()
      val fill = Fill.Blurred(radius = RADIUS, dim = 0f)
      val frame = probe.plan(composition(source, Fill.Solid(SLATE))).output.size
      val plain = probe.written(composition(source, Fill.Solid(SLATE)))
      val written = probe.written(composition(source, fill))
      val rect = placementOf(SOURCE, listOf(listOf(contain())), frame).drawnRect
      val midY = (rect.top + rect.bottom) / 2f
      val levels =
        mapOf(
          DARK to plain.green(rect.left + rect.width * (LIGHT_FROM / 2f) / SOURCE.width, midY),
          LIGHT to plain.green(rect.left + rect.width * ((LIGHT_FROM + LIGHT_TO) / 2f) / SOURCE.width, midY),
          MID to plain.green(rect.left + rect.width * HALF.toFloat(), midY),
        )

      val plan = fill.blurPlan(frame)
      val sigma = plan.sigmaPx / plan.width
      val cover = coverPlane(frame, levels, extra = 0)
      val expected = blurPlane(cover, DoubleArray(cover.size) { 1.0 }, frame.width, frame.height, sigma)
      // The same picture run on past the crop line, blurred at the same sigma in pixels.
      val wide = coverPlane(frame, levels, extra = BEYOND_PX)
      val wideWidth = frame.width + 2 * BEYOND_PX
      val bleeding =
        blurPlane(wide, DoubleArray(wide.size) { 1.0 }, wideWidth, frame.height, sigma * frame.width / wideWidth)

      val barY = rect.top / 2f
      val edgeX = EDGE_PX.toFloat() / frame.width
      val edge = expected.patchAt(edgeX, barY, frame)
      val bled = bleeding.patchAt(edgeX + BEYOND_PX.toFloat() / frame.width, barY, Size(wideWidth, frame.height))
      withClue(
        "a blur taking in the picture past the crop would read $bled at its edge, too close to the shared $edge",
      ) {
        abs(bled - edge) shouldBeGreaterThan 2 * SIGNAL_CODES
      }
      val stepX = ((LIGHT_TO.toDouble() / SOURCE.width - offsetOf(frame)) / widthFractionOf(frame)).toFloat()
      val step = expected.patchAt(stepX, barY, frame)
      val sharp = cover[(barY * frame.height).roundToInt() * frame.width + (stepX * frame.width).roundToInt()]
      withClue("the shared blur at the strip's edge reads $step against a sharp $sharp") {
        abs(step - sharp) shouldBeGreaterThan SIGNAL_CODES
      }

      for ((name, fx) in listOf("the crop edge" to edgeX, "the strip's edge" to stepX, "mid-frame" to HALF.toFloat())) {
        val wanted = expected.patchAt(fx, barY, frame)
        val read = written.green(fx, barY)
        withClue("the bar at $name read $read against the shared $wanted") {
          abs(read - wanted) shouldBeLessThan SIGNAL_CODES
        }
      }
    }

  // The cover of the picture, [extra] columns wider each side, each column read between the picture's pixels the way
  // a GPU's linear filter reads it.
  private fun coverPlane(
    frame: Size,
    levels: Map<Double, Double>,
    extra: Int,
  ): DoubleArray {
    val width = frame.width + 2 * extra
    val row =
      DoubleArray(width) { column ->
        val u = (column - extra + HALF) / frame.width * widthFractionOf(frame) + offsetOf(frame)
        val at = u * SOURCE.width - HALF
        val left = floor(at).toInt()
        val t = at - left
        val from = levels.getValue(levelAt(left.coerceIn(0, SOURCE.width - 1)))
        val to = levels.getValue(levelAt((left + 1).coerceIn(0, SOURCE.width - 1)))
        from + (to - from) * t
      }
    return DoubleArray(width * frame.height) { row[it % width] }
  }

  private fun widthFractionOf(frame: Size): Double = frame.width / (coverScale(SOURCE, frame).toDouble() * SOURCE.width)

  private fun offsetOf(frame: Size): Double = (1.0 - widthFractionOf(frame)) / 2.0

  private fun levelAt(x: Int): Double =
    when {
      x < LIGHT_FROM || x >= DARK_FROM -> DARK
      x < LIGHT_TO -> LIGHT
      else -> MID
    }

  // The shared plane averaged over the patch Written reads at the same point.
  private fun DoubleArray.patchAt(
    fx: Float,
    fy: Float,
    frame: Size,
  ): Double {
    val cx = (fx * frame.width).roundToInt()
    val cy = (fy * frame.height).roundToInt()
    var sum = 0.0
    for (y in cy - CODE_PATCH / 2 until cy + CODE_PATCH / 2) {
      for (x in cx - CODE_PATCH / 2 until cx + CODE_PATCH / 2) sum += this[y * frame.width + x]
    }
    return sum / (CODE_PATCH * CODE_PATCH)
  }

  private fun Written.green(
    fx: Float,
    fy: Float,
  ): Double = rgbAt(fx, fy).second.toDouble()

  private fun composition(
    source: MediaSource,
    fill: Fill,
  ): Composition = Composition(listOf(Track(listOf(Clip(source)))), listOf(contain()), fill = fill)

  private fun contain(): Crop = Crop(AspectRatio(1, 1), fit = Fit.Contain)

  private fun picture(): MediaSource {
    val file = File(probe.context.cacheDir, "fill-blur-strip.png")
    if (!file.exists()) {
      val pixels =
        IntArray(SOURCE.width * SOURCE.height) {
          val code = (levelAt(it % SOURCE.width) * MAX_CODE).roundToInt()
          OPAQUE or (code shl RED_SHIFT) or (code shl GREEN_SHIFT) or code
        }
      val bitmap = Bitmap.createBitmap(pixels, SOURCE.width, SOURCE.height, Bitmap.Config.ARGB_8888)
      file.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }
    return MediaSource.Image(ImageSource.of(file.path), LENGTH)
  }

  private companion object {
    val SOURCE = Size(640, 360)
    val LENGTH = 500.milliseconds

    // A square canvas over this picture crops its cover at 140 and 500 pixels across it.
    const val LIGHT_FROM = 136
    const val LIGHT_TO = 200
    const val DARK_FROM = 500

    // A sigma of 16 pixels on a 640 pixel square, which the plan boxes down by 2 onto whole pixels.
    const val RADIUS = 0.025f
    const val LIGHT = 0.8
    const val MID = 0.5
    const val DARK = 0.2
    const val SLATE = 0xFF2F4F6F.toInt()
    const val HALF = 0.5
    const val MAX_CODE = 255.0
    const val OPAQUE = 0xFF shl 24
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val PNG_QUALITY = 100

    // Far enough inside the frame for a whole read patch to land in it.
    const val EDGE_PX = 4

    // Past three sigmas of the blur, so the wider plane holds all the picture a blur there could take in.
    const val BEYOND_PX = 64

    // What an encoder moves a read of a gradient by.
    const val SIGNAL_CODES = 8.0
  }
}
