package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.flip
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.effects.overlay.imageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The built-in effects, checked against the pixels a real export writes.
 *
 * A generated clip carries no colour anyone can name, so each test paints one on: an opaque orange
 * badge is composited into a named corner of the clip's own frame, which is finished before any
 * composition geometry runs. The effect under test then moves, drops or dims that badge, and what
 * is asserted is where it ended up.
 *
 * Two things make the measurement trustworthy. Orange is the one colour ordering no bar of the test
 * pattern shares, red well above green well above blue. And every claim is the increase over the
 * same region of the same export without the badge, so whatever the pattern already drew there
 * cancels out. The first test is the control for the technique itself.
 *
 * Skipped when the fixtures are absent, as in [AppleExportTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleEffectTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `the marker lands in the corner it names`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val plain = exported(source, "marker-plain", marked = false) {}
      val marked = exported(source, "marker", marked = true) {}

      assertTrue(marked.gainedOver(plain, TOP_START) > COVERED, "no marker in the corner it was given")
      assertTrue(marked.gainedOver(plain, TOP_END) < UNTOUCHED, "the marker reached the far corner")
    }

  @Test
  fun `a horizontal flip mirrors the frame left to right`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val plain = exported(source, "flip-h-plain", marked = false) { flip(FlipAxis.Horizontal) }
      val marked = exported(source, "flip-h", marked = true) { flip(FlipAxis.Horizontal) }

      assertTrue(marked.gainedOver(plain, TOP_END) > COVERED, "the marker did not cross to the other side")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the marker stayed on the side it started")
    }

  @Test
  fun `a vertical flip mirrors the frame top to bottom`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val plain = exported(source, "flip-v-plain", marked = false) { flip(FlipAxis.Vertical) }
      val marked = exported(source, "flip-v", marked = true) { flip(FlipAxis.Vertical) }

      assertTrue(marked.gainedOver(plain, BOTTOM_START) > COVERED, "the marker did not cross to the bottom")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the marker stayed at the top")
    }

  // A quarter turn counter-clockwise carries the top edge round to the left one, so a badge in the
  // top-left corner comes to rest in the bottom-left.
  @Test
  fun `a quarter turn carries the frame round`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val plain = exported(source, "rotate-plain", marked = false) { rotate(QUARTER_TURN) }
      val marked = exported(source, "rotate", marked = true) { rotate(QUARTER_TURN) }

      assertTrue(marked.probe.height > marked.probe.width, "a quarter turn of a landscape clip is portrait")
      assertTrue(marked.gainedOver(plain, BOTTOM_START) > COVERED, "the marker did not turn with the frame")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the marker stayed where it was")
    }

  @Test
  fun `a crop rect keeps the half it named and drops the other`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val keptPlain = exported(source, "rect-kept-plain", marked = false) { crop(LEFT_HALF) }
      val kept = exported(source, "rect-kept", marked = true) { crop(LEFT_HALF) }
      val droppedPlain = exported(source, "rect-dropped-plain", marked = false) { crop(RIGHT_HALF) }
      val dropped = exported(source, "rect-dropped", marked = true) { crop(RIGHT_HALF) }

      assertTrue(kept.gainedOver(keptPlain, TOP_START) > COVERED, "the half holding the marker lost it")
      assertTrue(dropped.gainedOver(droppedPlain, TOP_START) < UNTOUCHED, "the marker survived a crop past it")
    }

  @Test
  fun `a crop rect takes the frame down to the region it named`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val cropped = exported(source, "rect-frame", marked = false) { crop(LEFT_HALF) }

      // The output frame is the planner's arithmetic rather than the resolver's, so this is the
      // half of the claim the sibling test's pixels cannot make: the frame the encoder was given
      // is the one the rect asks for. Half the width of a 16:9 clip is taller than it is wide.
      assertTrue(
        cropped.probe.height > cropped.probe.width,
        "half of a landscape frame came out ${cropped.probe.width}x${cropped.probe.height}",
      )
    }

  // Core Image works in linear light, so the multiply is wrapped in a tone curve to land on the
  // encoded signal the other three backends multiply. Against real pixels that is half the signal,
  // where multiplying the light instead would only take it down to about three quarters.
  @Test
  fun `halving brightness halves the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val full = exported(source, "brightness-full", marked = false) {}
      val half = exported(source, "brightness-half", marked = false) { brightness(HALF) }

      val lit = luminance(full.probe.average(CENTRE, CENTRE))
      val dimmed = luminance(half.probe.average(CENTRE, CENTRE))
      val ratio = dimmed.toFloat() / lit.toFloat()
      assertTrue(
        ratio in HALF - SIGNAL_DRIFT..HALF + SIGNAL_DRIFT,
        "half brightness read $dimmed against $lit at full, a ratio of $ratio",
      )
    }

  private suspend fun exported(
    source: String,
    name: String,
    marked: Boolean,
    onComposition: EffectsBuilder.() -> Unit,
  ): Frame {
    val composition =
      compositionOf {
        clip(MediaSource.of(source)) {
          trim(0.seconds, 1.seconds)
          if (marked) {
            effects { imageOverlay(ImageSource.ofBytes(ORANGE_PNG), Corner.TopStart, MARGIN, BADGE_SCALE) }
          }
        }
        effects(onComposition)
      }

    val output = temporaryPath(name)
    export(composition, output)
    return Frame(frameOf(output)).also { remove(output) }
  }

  private suspend fun export(
    composition: EditComposition,
    output: String,
  ) {
    withContext(Dispatchers.Default) {
      val statuses = filmstrip.export(composition, SPEC, MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> assertIs<ExportStatus.Success>(finished)
      }
    }
  }

  /**
   * One exported frame, measured for how much of a region reads as the badge.
   */
  private class Frame(
    val probe: FrameProbe,
  ) {
    /**
     * How much more of [region] reads as the badge colour here than in [plain], as a fraction of
     * the cells sampled.
     */
    fun gainedOver(
      plain: Frame,
      region: Region,
    ): Float = badgeFraction(region) - plain.badgeFraction(region)

    private fun badgeFraction(region: Region): Float {
      var hits = 0
      for (row in 0 until CELLS) {
        for (column in 0 until CELLS) {
          val x = region.left + (region.right - region.left) * (column + 0.5f) / CELLS
          val y = region.top + (region.bottom - region.top) * (row + 0.5f) / CELLS
          if (isBadgeAt(x, y)) hits++
        }
      }
      return hits.toFloat() / (CELLS * CELLS)
    }

    // Read as gaps between channels rather than as values, since an encode moves all three
    // together but leaves their ordering alone.
    private fun isBadgeAt(
      x: Float,
      y: Float,
    ): Boolean {
      val (red, green, blue) = probe.average(x, y)
      return red - green in CHANNEL_GAP && green - blue in CHANNEL_GAP && red - blue > CHANNEL_SPAN
    }
  }

  /**
   * A rectangle of the frame to measure, as fractions.
   */
  private class Region(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
  )

  private fun fixture(): String? {
    val directory = fixtures ?: return null
    val path = "$directory/apple_export_a.mp4"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-effect-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    const val FIXTURES = "FILMSTRIP_FIXTURES"
    val TIMEOUT = 2.seconds * 60
    val SPEC = ExportSpec(targetHeight = 180)

    const val QUARTER_TURN = 90
    const val HALF = 0.5f
    const val CENTRE = 0.5f

    val LEFT_HALF = NormalizedRect(0f, 0f, 0.5f, 1f)
    val RIGHT_HALF = NormalizedRect(0.5f, 0f, 1f, 1f)

    const val BADGE_SCALE = 0.3f
    const val MARGIN = 0.02f

    // A four by four opaque orange PNG, 255, 140, 0.
    val ORANGE_PNG =
      (
        "89504e470d0a1a0a0000000d4948445200000004000000040806000000" +
          "a9f19e7e000000124944415478da63f8dfc3f01f1933902e00006a2f28a1" +
          "8abdc82d0000000049454e44ae426082"
      ).decodeHex()

    val TOP_START = Region(0f, 0f, 0.3f, 0.3f)
    val TOP_END = Region(0.7f, 0f, 1f, 0.3f)
    val BOTTOM_START = Region(0f, 0.7f, 0.3f, 1f)

    const val CELLS = 6
    val CHANNEL_GAP = 40..190
    const val CHANNEL_SPAN = 140

    // The badge is opaque and covers its region, so a real hit turns most cells orange. A region it
    // never touched moves by a cell or two as the encoder rounds, and no more.
    const val COVERED = 0.6f
    const val UNTOUCHED = 0.1f

    // How far off half the encoder and a 4:2:0 round trip are allowed to leave the halved signal.
    // Halving linear light instead would read about three quarters of the signal, well outside
    // this, which is what makes the band worth asserting rather than a plain "darker".
    const val SIGNAL_DRIFT = 0.05f

    fun String.decodeHex(): ByteArray =
      ByteArray(length / 2) { index ->
        ((this[index * 2].digitToInt(HEX) shl 4) or this[index * 2 + 1].digitToInt(HEX)).toByte()
      }

    const val HEX = 16
  }
}
