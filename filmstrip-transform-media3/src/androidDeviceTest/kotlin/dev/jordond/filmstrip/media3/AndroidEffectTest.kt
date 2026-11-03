package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transform
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

/**
 * The built-in effects, checked against the pixels a real export writes.
 *
 * A generated clip carries no color anyone can name, so each test paints one on: an opaque orange
 * badge is composited into a named corner of the clip's own frame, which is finished before any
 * composition geometry runs. The effect under test then moves, drops or dims that badge, and what is
 * asserted is where it ended up. [theMarkerLandsInTheCornerItNames] is the control for the technique
 * itself.
 *
 * The overlays themselves are covered in [AndroidOverlayTest] and composition crops in
 * [AndroidGeometryTest]. Skipped when the fixtures are absent, as in [AndroidExportTest].
 */
class AndroidEffectTest {
  private val probe = Media3ExportProbe()

  @Test
  fun theMarkerLandsInTheCornerItNames() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      val plain = probe.frame(export(source, marked = false, effects = emptyList()))
      val marked = probe.frame(export(source, marked = true, effects = emptyList()))

      withClue("no marker in the corner it was given") { (marked.gainedOver(plain, TOP_START) > COVERED) shouldBe true }
      withClue("the marker reached the far corner") { (marked.gainedOver(plain, TOP_END) < UNTOUCHED) shouldBe true }
    }

  @Test
  fun aHorizontalFlipMirrorsTheFrameLeftToRight() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val mirror = listOf(Flip(FlipAxis.Horizontal))

      val plain = probe.frame(export(source, marked = false, effects = mirror))
      val marked = probe.frame(export(source, marked = true, effects = mirror))

      withClue("the marker did not cross to the other side") {
        (marked.gainedOver(plain, TOP_END) > COVERED) shouldBe true
      }
      withClue("the marker stayed on the side it started") {
        (marked.gainedOver(plain, TOP_START) < UNTOUCHED) shouldBe true
      }
    }

  @Test
  fun aVerticalFlipMirrorsTheFrameTopToBottom() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val mirror = listOf(Flip(FlipAxis.Vertical))

      val plain = probe.frame(export(source, marked = false, effects = mirror))
      val marked = probe.frame(export(source, marked = true, effects = mirror))

      withClue("the marker did not cross to the bottom") {
        (marked.gainedOver(plain, BOTTOM_START) > COVERED) shouldBe true
      }
      withClue("the marker stayed at the top") { (marked.gainedOver(plain, TOP_START) < UNTOUCHED) shouldBe true }
    }

  // A quarter turn counter-clockwise carries the top edge round to the left one, so a badge in the
  // top-left corner comes to rest in the bottom-left.
  @Test
  fun aQuarterTurnCarriesTheFrameRound() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val turn = listOf(Rotate(QUARTER_TURN))

      val plain = probe.frame(export(source, marked = false, effects = turn))
      val marked = probe.frame(export(source, marked = true, effects = turn))

      withClue("a quarter turn of a landscape clip is portrait") { (marked.height > marked.width) shouldBe true }
      withClue("the marker did not turn with the frame") {
        (marked.gainedOver(plain, BOTTOM_START) > COVERED) shouldBe true
      }
      withClue("the marker stayed where it was") { (marked.gainedOver(plain, TOP_START) < UNTOUCHED) shouldBe true }
    }

  @Test
  fun aCropRectKeepsTheHalfItNamedAndDropsTheOther() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val keep = listOf(CropRect(LEFT_HALF))
      val drop = listOf(CropRect(RIGHT_HALF))

      val keptPlain = probe.frame(export(source, marked = false, effects = keep))
      val kept = probe.frame(export(source, marked = true, effects = keep))
      val droppedPlain = probe.frame(export(source, marked = false, effects = drop))
      val dropped = probe.frame(export(source, marked = true, effects = drop))

      withClue("the half holding the marker lost it") {
        (kept.gainedOver(keptPlain, TOP_START) > COVERED) shouldBe true
      }
      withClue("the marker survived a crop past it") {
        (dropped.gainedOver(droppedPlain, TOP_START) < UNTOUCHED) shouldBe true
      }
    }

  @Test
  fun aCropRectTakesTheFrameDownToTheRegionItNamed() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      val cropped = probe.frame(export(source, marked = false, effects = listOf(CropRect(LEFT_HALF))))

      // The output frame is the planner's arithmetic rather than the resolver's, so this is the half
      // of the claim the sibling test's pixels cannot make: the frame the encoder was given is the
      // one the rect asks for. Half the width of a 16:9 clip is taller than it is wide.
      withClue("half of a landscape frame came out ${cropped.width}x${cropped.height}") {
        (cropped.height > cropped.width) shouldBe true
      }
    }

  // media3 keeps the input's own transfer function as its SDR working color space, so the color
  // matrix multiplies the encoded signal. Against real pixels that is half the signal, where
  // multiplying the light instead would only take it down to about three quarters.
  @Test
  fun halvingBrightnessHalvesTheSignal() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      val full = probe.frame(export(source, marked = false, effects = emptyList()))
      val half = probe.frame(export(source, marked = false, effects = listOf(Brightness(HALF))))

      val lit = luminance(full.averageAt(CENTRE, CENTRE))
      val dimmed = luminance(half.averageAt(CENTRE, CENTRE))
      val ratio = dimmed.toFloat() / lit.toFloat()
      withClue("half brightness read $dimmed against $lit at full, a ratio of $ratio") {
        (ratio in HALF - SIGNAL_DRIFT..HALF + SIGNAL_DRIFT) shouldBe true
      }
    }

  // A contrast pivots on mid grey, so its matrix carries a bias as well as a gain, and the expected
  // pixel comes from the shared matrix rather than from a ratio. Half rather than a gain above one:
  // the pattern's bars are saturated, and stretching one takes every channel of it past an endpoint,
  // where a backend that dropped the bias would read the same.
  @Test
  fun aContrastPullsEveryChannelTowardsMidGrey() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val spec = Contrast(HALF)

      val plain = probe.frame(export(source, marked = false, effects = emptyList()))
      val graded = probe.frame(export(source, marked = false, effects = listOf(spec)))

      assertGraded(plain, graded, spec, BLUE_BAR)
    }

  // Saturation is the one that mixes channels, so its rows have to be the right way round. A cyan
  // bar reads that back: two channels come down towards the luma while the third climbs to meet it.
  @Test
  fun aHalfSaturationMovesEveryChannelHalfwayToItsGrey() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val spec = Saturation(HALF)

      val plain = probe.frame(export(source, marked = false, effects = emptyList()))
      val graded = probe.frame(export(source, marked = false, effects = listOf(spec)))

      assertGraded(plain, graded, spec, CYAN_BAR)
    }

  // The endpoint where nothing but the bias survives, checked on two bars that started at opposite
  // ends of the range and have to land on the same mid grey.
  @Test
  fun aZeroContrastFlattensTheFrameToMidGrey() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val spec = Contrast(0f)

      val plain = probe.frame(export(source, marked = false, effects = emptyList()))
      val flat = probe.frame(export(source, marked = false, effects = listOf(spec)))

      assertGraded(plain, flat, spec, CYAN_BAR)
      assertGraded(plain, flat, spec, BLUE_BAR)
    }

  private suspend fun export(
    source: MediaSource,
    marked: Boolean,
    effects: List<EffectSpec>,
  ): File {
    val marker =
      if (marked) {
        listOf(ImageOverlay(ImageSource.of(badgeFile(probe.context).path), Corner.TopStart, MARGIN, BADGE_SCALE))
      } else {
        emptyList()
      }
    return probe.export(EditComposition(listOf(Track(listOf(Clip(source, effects = marker)))), effects))
  }

  // What the shared matrix predicts for the pixel the plain export wrote, against what the graded
  // export wrote in its place. Measuring the plain frame rather than the source is what keeps the
  // encoder and the 4:2:0 round trip out of the comparison, since both frames carry them.
  private fun assertGraded(
    plain: Bitmap,
    graded: Bitmap,
    spec: EffectSpec,
    fx: Float,
  ) {
    val before = plain.averageAt(fx, CENTRE)
    val after = graded.averageAt(fx, CENTRE)
    val matrix = checkNotNull(colorMatrixOf(spec)) { "$spec is not a color matrix" }
    val expected = matrix.transform(before.first / FULL, before.second / FULL, before.third / FULL)
    val predicted = Triple(code(expected[0]), code(expected[1]), code(expected[2]))

    withClue("$spec left the pixel at $fx on $before, so nothing was graded") {
      (distance(after, before) > MOVED) shouldBe true
    }
    withClue("$spec read $after at $fx, where the shared matrix says $predicted from $before") {
      (distance(after, predicted) < GRADE_DRIFT) shouldBe true
    }
  }

  private fun code(channel: Float): Int = (channel * FULL).toInt()

  private companion object {
    const val QUARTER_TURN = 90
    const val HALF = 0.5f
    const val CENTRE = 0.5f
    const val MARGIN = 0.02f
    const val FULL = 255f

    // The pattern's own centre sits on the seam between two color bars, where the patch averages
    // both sides and reads as a grey no pixel in the frame actually holds. A matrix that mixes
    // channels needs a color, so these name the middle of a bar instead. Both lie in the band of
    // bars that crosses the frame at [CENTRE].
    const val CYAN_BAR = 0.19f
    const val BLUE_BAR = 0.44f

    val LEFT_HALF = NormalizedRect(0f, 0f, 0.5f, 1f)
    val RIGHT_HALF = NormalizedRect(0.5f, 0f, 1f, 1f)

    // How far off half the encoder and a 4:2:0 round trip are allowed to leave the halved signal.
    // Halving linear light instead would read about 0.73 of the signal, still well outside this,
    // which is what makes the band worth asserting rather than a plain "darker". Wide enough for
    // a device whose decode path leaves the ratio nearer 0.56 than 0.5.
    const val SIGNAL_DRIFT = 0.1f

    // Summed across the three channels, for the two encodes and the two 4:2:0 round trips behind
    // the comparison. Reading the plain frame rather than the source leaves almost nothing to
    // absorb: an emulator lands every probe here within three of the matrix. The headroom is for
    // other encoders, and it is still an order of magnitude under what a dropped bias or a
    // transposed row would move a bar by.
    const val GRADE_DRIFT = 24

    // Far enough that a graded pixel cannot have been left where it started. Without it a probe
    // that drifted onto a pixel the matrix happens to fix would pass having measured nothing.
    const val MOVED = 60
  }
}
