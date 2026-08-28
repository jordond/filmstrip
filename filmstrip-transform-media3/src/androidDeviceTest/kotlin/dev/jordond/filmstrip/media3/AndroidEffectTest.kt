package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.effects.CropRect
import dev.jordond.filmstrip.effects.Flip
import dev.jordond.filmstrip.effects.Rotate
import dev.jordond.filmstrip.effects.Watermark
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The built-in effects, checked against the pixels a real export writes.
 *
 * A generated clip carries no colour anyone can name, so each test paints one on: an opaque orange
 * badge is composited into a named corner of the clip's own frame, which is finished before any
 * composition geometry runs. The effect under test then moves, drops or dims that badge, and what is
 * asserted is where it ended up. [theMarkerLandsInTheCornerItNames] is the control for the technique
 * itself.
 *
 * The overlays themselves are covered in [AndroidOverlayTest] and composition crops in
 * [AndroidGeometryTest]. Skipped when the fixtures are absent, as in [AndroidExportTest].
 */
class AndroidEffectTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val filmstrip = Filmstrip(context) { media3Backend() }

  @Test
  fun theMarkerLandsInTheCornerItNames() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val plain = frame(export(source, marked = false, effects = emptyList()))
      val marked = frame(export(source, marked = true, effects = emptyList()))

      assertTrue(marked.gainedOver(plain, TOP_START) > COVERED, "no marker in the corner it was given")
      assertTrue(marked.gainedOver(plain, TOP_END) < UNTOUCHED, "the marker reached the far corner")
    }

  @Test
  fun aHorizontalFlipMirrorsTheFrameLeftToRight() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val mirror = listOf(Flip(FlipAxis.Horizontal))

      val plain = frame(export(source, marked = false, effects = mirror))
      val marked = frame(export(source, marked = true, effects = mirror))

      assertTrue(marked.gainedOver(plain, TOP_END) > COVERED, "the marker did not cross to the other side")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the marker stayed on the side it started")
    }

  @Test
  fun aVerticalFlipMirrorsTheFrameTopToBottom() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val mirror = listOf(Flip(FlipAxis.Vertical))

      val plain = frame(export(source, marked = false, effects = mirror))
      val marked = frame(export(source, marked = true, effects = mirror))

      assertTrue(marked.gainedOver(plain, BOTTOM_START) > COVERED, "the marker did not cross to the bottom")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the marker stayed at the top")
    }

  // A quarter turn counter-clockwise carries the top edge round to the left one, so a badge in the
  // top-left corner comes to rest in the bottom-left.
  @Test
  fun aQuarterTurnCarriesTheFrameRound() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val turn = listOf(Rotate(QUARTER_TURN))

      val plain = frame(export(source, marked = false, effects = turn))
      val marked = frame(export(source, marked = true, effects = turn))

      assertTrue(marked.height > marked.width, "a quarter turn of a landscape clip is portrait")
      assertTrue(marked.gainedOver(plain, BOTTOM_START) > COVERED, "the marker did not turn with the frame")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the marker stayed where it was")
    }

  @Test
  fun aCropRectKeepsTheHalfItNamedAndDropsTheOther() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      val keep = listOf(CropRect(LEFT_HALF))
      val drop = listOf(CropRect(RIGHT_HALF))

      val keptPlain = frame(export(source, marked = false, effects = keep))
      val kept = frame(export(source, marked = true, effects = keep))
      val droppedPlain = frame(export(source, marked = false, effects = drop))
      val dropped = frame(export(source, marked = true, effects = drop))

      assertTrue(kept.gainedOver(keptPlain, TOP_START) > COVERED, "the half holding the marker lost it")
      assertTrue(dropped.gainedOver(droppedPlain, TOP_START) < UNTOUCHED, "the marker survived a crop past it")
    }

  @Test
  fun aCropRectTakesTheFrameDownToTheRegionItNamed() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val cropped = frame(export(source, marked = false, effects = listOf(CropRect(LEFT_HALF))))

      // The output frame is the planner's arithmetic rather than the resolver's, so this is the half
      // of the claim the sibling test's pixels cannot make: the frame the encoder was given is the
      // one the rect asks for. Half the width of a 16:9 clip is taller than it is wide.
      assertTrue(
        cropped.height > cropped.width,
        "half of a landscape frame came out ${cropped.width}x${cropped.height}",
      )
    }

  // media3 keeps the input's own transfer function as its SDR working colour space, so the colour
  // matrix multiplies the encoded signal. Against real pixels that is half the signal, where
  // multiplying the light instead would only take it down to about three quarters.
  @Test
  fun halvingBrightnessHalvesTheSignal() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val full = frame(export(source, marked = false, effects = emptyList()))
      val half = frame(export(source, marked = false, effects = listOf(Brightness(HALF))))

      val lit = luminance(full.averageAt(CENTRE, CENTRE))
      val dimmed = luminance(half.averageAt(CENTRE, CENTRE))
      val ratio = dimmed.toFloat() / lit.toFloat()
      assertTrue(
        ratio in HALF - SIGNAL_DRIFT..HALF + SIGNAL_DRIFT,
        "half brightness read $dimmed against $lit at full, a ratio of $ratio",
      )
    }

  private suspend fun export(
    source: MediaSource,
    marked: Boolean,
    effects: List<EffectSpec>,
  ): File {
    val marker =
      if (marked) {
        listOf(Watermark(ImageSource.of(badgeFile(context).path), Corner.TopStart, MARGIN, BADGE_SCALE))
      } else {
        emptyList()
      }
    val composition = EditComposition(listOf(Track(listOf(Clip(source, effects = marker)))), effects)

    val plan =
      when (val verdict = filmstrip.plan(composition, SPEC)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> throw AssertionError("refused: ${verdict.reasons.map { it.message }}")
      }

    val statuses = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.temporary()).toList() }
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) throw AssertionError("export failed: ${finished.error.message}")
    return File((assertIs<ExportStatus.Success>(finished).output as MediaSink.Path).path)
  }

  private fun frame(video: File): Bitmap = frameOf(video, MID_CLIP)

  private fun fixture(name: String = CLIP): MediaSource? {
    val stream = javaClass.classLoader?.getResourceAsStream(name) ?: return null
    val file = File(context.cacheDir, name)
    stream.use { input -> file.outputStream().use(input::copyTo) }
    return MediaSource.of(file.path)
  }

  private companion object {
    val TIMEOUT = 5.minutes
    val SPEC = ExportSpec(targetHeight = 240)
    val MID_CLIP = 1_000.milliseconds

    const val CLIP = "android_export_a.mp4"

    const val QUARTER_TURN = 90
    const val HALF = 0.5f
    const val CENTRE = 0.5f
    const val MARGIN = 0.02f

    val LEFT_HALF = NormalizedRect(0f, 0f, 0.5f, 1f)
    val RIGHT_HALF = NormalizedRect(0.5f, 0f, 1f, 1f)

    val TOP_START = Region(0f, 0f, 0.3f, 0.3f)
    val TOP_END = Region(0.7f, 0f, 1f, 0.3f)
    val BOTTOM_START = Region(0f, 0.7f, 0.3f, 1f)

    // How far off half the encoder and a 4:2:0 round trip are allowed to leave the halved signal.
    // Halving linear light instead would read about 0.73 of the signal, still well outside this,
    // which is what makes the band worth asserting rather than a plain "darker". Wide enough for
    // a device whose decode path leaves the ratio nearer 0.56 than 0.5.
    const val SIGNAL_DRIFT = 0.1f
  }
}
