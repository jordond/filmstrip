package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.regionAt
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.AndroidKenBurnsTest.Companion.BOUNDARY
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.test.writeAtomically
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A pan over a photo, read off the pixels a real export wrote.
 *
 * The photo is red on the left and blue on the right, and the pan travels from a window inside the
 * red half to one inside the blue half, so how much red survives is a direct reading of which
 * region the transform cut out. It is compared against `regionAt`, the same function the Apple
 * lowering draws from, rather than against a number copied out of it. A backend that eased a curve
 * of its own fails here and so does one that agreed only at the two ends.
 *
 * A pan at a constant rate is read at 40% and 60% through the span for that reason, and a pan on a
 * curve at the halfway point, where the curve is furthest from the straight line.
 */
class AndroidKenBurnsTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aPanOverAPhotoShowsTheRegionTheSharedInterpolationNames() =
    runTest(timeout = TIMEOUT) {
      val edit = composition(Clip(stillSource(), effects = listOf(PAN)))
      val span = TimeRange.of(Duration.ZERO, PHOTO)

      val written = export(edit)

      FRACTIONS.forEach { fraction ->
        val at = PHOTO * fraction
        assertRedFraction(frameOf(written, at), PAN.regionAt(at, span), "at $fraction through the photo")
      }
    }

  /**
   * The same pan, on a photo that does not start at zero.
   *
   * media3 measures a clip effect's presentation time from the start of the sequence rather than
   * from the start of the item, so a lowering that subtracted the wrong start would hold the first
   * region for the whole of this span. Reading it against the composition-relative slot is what
   * separates the two.
   */
  @Test
  fun aPanIsMeasuredFromWhereTheClipSitsOnTheCompositionTimeline() =
    runTest(timeout = TIMEOUT) {
      val opening = probe.fixture(CLIP)
      val edit = composition(Clip(opening), Clip(stillSource(), effects = listOf(PAN)))
      val span = TimeRange.of(CLIP_LENGTH, CLIP_LENGTH + PHOTO)

      val written = export(edit)

      FRACTIONS.forEach { fraction ->
        val at = CLIP_LENGTH + PHOTO * fraction
        assertRedFraction(frameOf(written, at), PAN.regionAt(at, span), "at $fraction through the photo")
      }
    }

  /**
   * The same pan, paced by a curve rather than at a constant rate.
   *
   * A lowering that interpolated the two regions itself instead of reading the shared one draws the
   * straight-line position whatever curve it was handed. At the halfway point that is a quarter of
   * the travel from where either of these curves sits, which this window reads as nearly four times
   * the slack the measurement allows.
   */
  @Test
  fun aCurvedPanShowsTheRegionItsCurveNames() =
    runTest(timeout = TIMEOUT) {
      val span = TimeRange.of(Duration.ZERO, PHOTO)
      val at = PHOTO * MIDPOINT

      CURVES.forEach { easing ->
        val pan = KenBurns(PAN.from, PAN.to, easing)
        val written = export(composition(Clip(stillSource(), effects = listOf(pan))))

        assertRedFraction(frameOf(written, at), pan.regionAt(at, span), "halfway through a $easing pan")
      }
    }

  /**
   * A pan is a pan whether the frames under it were decoded or held, so a video clip takes one on
   * the same terms a photo does.
   */
  @Test
  fun aPanOverAVideoClipMovesTheFrameAsItRuns() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(CLIP)
      val panned = export(composition(Clip(source, effects = listOf(PAN))))
      val plain = export(composition(Clip(source)))

      val early = frameOf(panned, CLIP_LENGTH * FRACTIONS.first())
      val late = frameOf(panned, CLIP_LENGTH * FRACTIONS.last())
      val untouched = frameOf(plain, CLIP_LENGTH * FRACTIONS.first())

      assertTrue(
        distance(early.averageAt(CENTRE, CENTRE), late.averageAt(CENTRE, CENTRE)) > MOVED,
        "the panned video showed the same colour at both readings, so nothing travelled",
      )
      assertTrue(
        distance(early.averageAt(CENTRE, CENTRE), untouched.averageAt(CENTRE, CENTRE)) > MOVED,
        "the panned video matched the untouched one, so the pan resolved to nothing",
      )
    }

  private fun assertRedFraction(
    frame: Bitmap,
    region: NormalizedRect,
    where: String,
  ) {
    val expected = ((BOUNDARY - region.left) / region.width).coerceIn(0f, 1f)
    val measured = frame.redSpan()

    assertTrue(
      abs(measured - expected) < TOLERANCE,
      "$where the region $region should leave $expected red, measured $measured",
    )
  }

  /**
   * The share of one full-width row that came out of the red half of the photo.
   *
   * Red against blue rather than a colour match, since the two are opposite ends of the one channel
   * an encoder cannot move without moving the other.
   */
  private fun Bitmap.redSpan(): Float {
    var red = 0
    for (column in 0 until SAMPLES) {
      val (r, _, b) = averageAt((column + 0.5f) / SAMPLES, CENTRE)
      if (r > b) red++
    }
    return red.toFloat() / SAMPLES
  }

  private suspend fun export(composition: EditComposition): File = probe.export(composition, ExportSpec())

  private fun composition(vararg clips: Clip) = EditComposition(listOf(Track(clips.toList())))

  private fun stillSource() = MediaSource.Image(ImageSource.of(splitPhotoFile().path), PHOTO)

  /**
   * A photo that is red on the left of [BOUNDARY] and blue on the right, written into the cache
   * once.
   */
  private fun splitPhotoFile(): File {
    val file = File(probe.context.cacheDir, "ken-burns-split.png")
    if (file.exists()) return file

    val bitmap = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    val edge = PHOTO_WIDTH * BOUNDARY
    paint.color = Color.RED
    canvas.drawRect(0f, 0f, edge, PHOTO_HEIGHT.toFloat(), paint)
    paint.color = Color.BLUE
    canvas.drawRect(edge, 0f, PHOTO_WIDTH.toFloat(), PHOTO_HEIGHT.toFloat(), paint)
    file.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    return file
  }

  private companion object {
    // What the fixture generator was asked for, so a change to it fails here rather than drifting.
    val CLIP_LENGTH = 2.seconds

    // Long enough that the frame a retriever lands on either side of a request moves the reading by
    // well under the slack the boundary itself costs.
    val PHOTO = 4.seconds

    // Neither end of the span, and either side of its halfway point, which a symmetric curve
    // agrees with a straight line on.
    val FRACTIONS = listOf(0.4, 0.6)

    // The halfway point is where a curve that only accelerates and one that only decelerates sit
    // furthest from a straight line, a quarter of the travel either side of it.
    const val MIDPOINT = 0.5

    // EaseInOut is left to the Apple reading, since it never leaves the straight line by enough for
    // this path's TOLERANCE to tell the two apart.
    val CURVES = listOf(Easing.EaseIn, Easing.EaseOut)

    const val BOUNDARY = 0.5f

    // Windows wide enough that the boundary is a long way inside both readings, travelling far
    // enough that 40% and 60% are plainly different frames.
    val PAN =
      KenBurns(
        from = NormalizedRect(0f, 0f, 0.4f, 1f),
        to = NormalizedRect(0.6f, 0f, 1f, 1f),
        easing = Easing.Linear,
      )

    const val PHOTO_WIDTH = 640
    const val PHOTO_HEIGHT = 360

    const val CENTRE = 0.5f
    const val SAMPLES = 40

    // Two of forty columns for where the boundary resamples, and two more for the frame the
    // retriever lands on either side of the one asked for.
    const val TOLERANCE = 0.1f

    // Well past what an encode moves a flat patch by, and well under what the pattern's own bars
    // differ from each other by.
    const val MOVED = 60
  }
}
