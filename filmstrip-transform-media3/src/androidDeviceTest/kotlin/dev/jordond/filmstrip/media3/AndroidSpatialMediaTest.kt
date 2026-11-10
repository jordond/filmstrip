package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.effects.spatial.GaussianBlur
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.CODE_PATCH
import dev.jordond.filmstrip.test.writeAtomically
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Where a [GaussianBlur] lands in a real media3 export: on a contained clip at each scope, and beside an overlay.
 *
 * The picture is a light half and a dark half, so a read across its middle tells a blur from a sharp frame, and every
 * blurred read is held against [blurPlane] over the plain export's own levels. How close each pixel of the blur lands
 * is read off the shader's own output in the effects module, so the reads here allow for what an encoder moves.
 */
class AndroidSpatialMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aClipBlurOnAContainClipBlursThePictureByItselfAndLeavesTheBarsTheFill() =
    runTest(timeout = TIMEOUT) {
      assertContained(contained(listOf(contain(), GaussianBlur(SIGMA))))
    }

  @Test
  fun aCompositionBlurOnAContainClipBlursThePictureByItselfAndLeavesTheBarsTheFill() =
    runTest(timeout = TIMEOUT) {
      assertContained(contained(listOf(contain()), listOf(GaussianBlur(SIGMA))))
    }

  @Test
  fun anOverlayAtTheSameScopeStaysSharp() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(barsClip())
      val swatch = swatchOverlay()
      val (read, wanted) =
        swatchEdgeReads(
          Composition(listOf(Track(listOf(clip))), listOf(swatch, GaussianBlur(SIGMA))),
          Composition(listOf(Track(listOf(clip))), listOf(swatch)),
        )

      withClue("the swatch's edge read $read against $wanted with no blur") {
        abs(read - wanted) shouldBeLessThan ENCODER_CODES
      }
    }

  @Test
  fun aClipOverlayUnderACompositionBlurIsBlurred() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(barsClip(), effects = listOf(swatchOverlay()))
      val (read, wanted) =
        swatchEdgeReads(
          Composition(listOf(Track(listOf(clip))), listOf(GaussianBlur(SIGMA))),
          Composition(listOf(Track(listOf(clip)))),
        )

      withClue("the swatch's edge read $read against $wanted with no blur") {
        abs(read - wanted) shouldBeGreaterThan ENCODER_CODES
      }
    }

  // The picture beside its own edge blurs by the shared plan with the bars counting for nothing, the picture's row
  // beside a bar keeps the picture's level, and the bar reads the fill.
  private suspend fun assertContained(blurred: Composition) {
    val plan = probe.plan(contained(listOf(contain())))
    val frame = plan.output.size
    val plain = probe.writtenBy(probe.finish(plan))
    val written = probe.written(blurred)
    val rect = placementOf(SOURCE, listOf(listOf(contain())), frame).drawnRect
    val midX = (rect.left + rect.right) / 2f
    val midY = (rect.top + rect.bottom) / 2f
    val light = plain.green(rect.left + rect.width / 4f, midY)
    val dark = plain.green(rect.right - rect.width / 4f, midY)
    val fill = plain.green(midX, rect.top / 2f)

    val width = frame.width
    val height = frame.height
    val inside = { x: Int, y: Int ->
      (x + HALF) / width in rect.left..rect.right && (y + HALF) / height in rect.top..rect.bottom
    }
    val plane =
      DoubleArray(width * height) {
        val x = it % width
        val y = it / width
        if (!inside(x, y)) {
          fill
        } else if ((x + HALF) / width < midX) {
          light
        } else {
          dark
        }
      }
    val alpha = DoubleArray(plane.size) { if (inside(it % width, it / width)) 1.0 else 0.0 }
    val shared = blurPlane(plane, alpha, width, height, SIGMA.toDouble())
    val bleeding = blurPlane(plane, DoubleArray(plane.size) { 1.0 }, width, height, SIGMA.toDouble())

    val acrossX = midX + SIGMA / 2f
    val across = shared.patchAt(acrossX, midY, frame)
    withClue("the shared blur half a sigma past the picture's middle reads $across against a sharp $dark") {
      abs(across - dark) shouldBeGreaterThan SIGNAL_CODES
    }
    val acrossRead = written.green(acrossX, midY)
    withClue("half a sigma past the picture's middle read $acrossRead against the shared $across") {
      abs(acrossRead - across) shouldBeLessThan SIGNAL_CODES
    }

    val edgeX = rect.left + rect.width / 4f
    val edgeY = rect.top + EDGE_PX.toFloat() / height
    val edge = shared.patchAt(edgeX, edgeY, frame)
    val bled = bleeding.patchAt(edgeX, edgeY, frame)
    withClue("a blur taking in the bar would read $bled beside it, too close to the shared $edge to tell") {
      abs(bled - edge) shouldBeGreaterThan SIGNAL_CODES
    }
    val edgeRead = written.green(edgeX, edgeY)
    withClue("the picture beside the bar read $edgeRead against the shared $edge") {
      abs(edgeRead - edge) shouldBeLessThan SIGNAL_CODES
    }

    val bar = written.green(midX, rect.top / 2f)
    withClue("the bar read $bar against the fill's $fill") { abs(bar - fill) shouldBeLessThanOrEqual BAR_CODES }
  }

  // The red of the swatch just inside its trailing edge, where a blur would take in the picture beside it, read off
  // [edit] and then off [plain].
  private suspend fun swatchEdgeReads(
    edit: Composition,
    plain: Composition,
  ): Pair<Double, Double> {
    val frame = probe.plan(plain).output.size
    val rect = swatchOverlay().placedOn(frame, Size(SWATCH_PX, SWATCH_PX)).rectOn(frame)
    val fx = rect.right - EDGE_PX.toFloat() / frame.width
    val fy = (rect.top + rect.bottom) / 2f
    return probe
      .written(edit)
      .rgbAt(fx, fy)
      .first
      .toDouble() to
      probe
        .written(plain)
        .rgbAt(fx, fy)
        .first
        .toDouble()
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

  private suspend fun contained(
    clipEffects: List<EffectSpec>,
    effects: List<EffectSpec> = emptyList(),
  ): Composition =
    Composition(listOf(Track(listOf(Clip(barsClip(), effects = clipEffects)))), effects, fill = Fill.Solid(SLATE))

  private fun contain(): Crop = Crop(SQUARE, fit = Fit.Contain)

  private suspend fun barsClip(): MediaSource {
    val file = File(probe.context.cacheDir, "spatial-halves.mp4")
    if (!file.exists()) {
      val light = LIGHT.toFloat()
      val dark = DARK.toFloat()
      probe.writeBarsClip(
        file,
        SOURCE.width,
        SOURCE.height,
        listOf(floatArrayOf(light, light, light), floatArrayOf(dark, dark, dark)),
      )
    }
    return MediaSource.of(file.path)
  }

  private fun swatchOverlay(): ImageOverlay {
    val file = File(probe.context.cacheDir, "spatial-swatch.png")
    if (!file.exists()) {
      val bitmap = Bitmap.createBitmap(SWATCH_PX, SWATCH_PX, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(SWATCH_COLOR)
      file.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }
    return ImageOverlay(ImageSource.of(file.path), Corner.TopStart, scale = SWATCH_SCALE)
  }

  private companion object {
    val SOURCE = Size(640, 360)

    // A square canvas over a landscape clip leaves a bar above and below the picture.
    val SQUARE = AspectRatio(1, 1)
    const val SIGMA = 0.05f
    const val LIGHT = 0.8
    const val DARK = 0.3
    const val SLATE = 0xFF2F4F6F.toInt()
    const val HALF = 0.5

    // Far enough inside an edge for a whole read patch to land on the near side of it.
    const val EDGE_PX = 4

    // Blue, which reads no red against a picture with plenty.
    const val SWATCH_COLOR = 0xFF0000FF.toInt()
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val PNG_QUALITY = 100

    // What an encoder moves a flat read by, and a read of a gradient.
    const val ENCODER_CODES = 5.0
    const val BAR_CODES = 2.0
    const val SIGNAL_CODES = 8.0
  }
}
