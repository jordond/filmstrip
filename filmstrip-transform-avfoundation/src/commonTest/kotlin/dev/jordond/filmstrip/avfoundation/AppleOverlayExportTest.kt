package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.imageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.effects.overlay.textOverlay
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The composite half of the pipeline, exported for real.
 *
 * The placement arithmetic is asserted in `filmstrip-effects`, where it is pure. What these cover
 * is that a composited chain survives a whole export. The frames render, the file is written, and
 * the plan does not quietly refuse the effect on the way.
 *
 * The fade is the one case that reads the written file back, since a property that changes per frame
 * is one nothing upstream of the encoder can prove arrived.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleOverlayExportTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `burns a watermark into every corner`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      Corner.entries.forEach { corner ->
        val composition =
          compositionOf {
            clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
            effects { imageOverlay(ImageSource.ofBytes(RED_PNG), corner, margin = 0.04f, scale = 0.2f) }
          }

        val output = temporaryPath("watermark-$corner")
        val success = exported(composition, ExportSpec(targetHeight = 180), output)
        success.info.video!!.displaySize shouldBe Size(320, 180)
        remove(output)
      }
    }

  @Test
  fun `burns text into the frame`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
          effects { textOverlay("filmstrip", TextStyle(fontSize = 0.12f, backgroundColor = BLACK)) }
        }

      val output = temporaryPath("text")
      val success = exported(composition, ExportSpec(targetHeight = 180), output)

      success.info.video!!.displaySize shouldBe Size(320, 180)
      remove(output)
    }

  @Test
  fun `dims the whole frame`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape)) { trim(0.seconds, 1.seconds) }
          effects { brightness(0.5f) }
        }

      val output = temporaryPath("brightness")
      exported(composition, ExportSpec(targetHeight = 180), output)
      remove(output)
    }

  // Whether the overlay is drawn on a given frame is asserted step by step in filmstrip-effects,
  // where it is pure. This is the check that a timed overlay reaches the encoder at all instead of
  // being refused while planning.
  @Test
  fun `exports a watermark that appears for part of the composition`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape))
          effects {
            imageOverlay(
              ImageSource.ofBytes(RED_PNG),
              Corner.TopEnd,
              visibleDuring = TimeRange(500.milliseconds, 1_500.milliseconds),
            )
          }
        }

      val verdict = withContext(Dispatchers.Default) { filmstrip.plan(composition, ExportSpec(targetHeight = 180)) }
      assertIs<Verdict.Capable>(verdict)

      val output = temporaryPath("timed-watermark")
      exported(composition, ExportSpec(targetHeight = 180), output)
      remove(output)
    }

  // Read inside the written file at a quarter and three quarters of the ramp. Both ends of a ramp
  // agree whether the opacity is added or multiplied in, so only the middle says which one ran, and
  // the expectation comes from the shared sampler rather than from a number copied out of it.
  @Test
  fun `burns a fade into the middle of its ramp`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      val faded = watermark(fadeIn(RAMP))
      val bare = exportOf(landscape, null, "fade-bare")
      val opaque = exportOf(landscape, watermark(null), "fade-opaque")
      val ramped = exportOf(landscape, faded, "fade-ramp")
      val rect = faded.placedOn(OUTPUT, RASTER).rectOn(OUTPUT)

      RAMP_POINTS.forEach { time ->
        val opacity = assertNotNull(faded.frameAt(time, SPAN), "the watermark was not drawn at $time").opacity
        val (whole, moved) = coveredBy(rect, frameOf(opaque, time), frameOf(ramped, time), frameOf(bare, time))

        assertTrue(whole > FLOOR, "the opaque watermark only moved $whole of the frame, so no fade is measurable")
        assertEquals(opacity * whole, moved, whole * DRIFT, "a fade at $time moved $moved of a full $whole")
      }

      listOf(bare, opaque, ramped).forEach(::remove)
    }

  @Test
  fun `refuses a watermark whose image cannot be read`() =
    runTest(timeout = TIMEOUT) {
      val landscape = fixture() ?: return@runTest

      val composition =
        compositionOf {
          clip(MediaSource.of(landscape))
          effects { imageOverlay(ImageSource.of("/nonexistent/badge.png"), Corner.TopStart) }
        }

      val verdict = withContext(Dispatchers.Default) { filmstrip.plan(composition, ExportSpec(targetHeight = 180)) }
      val incapable = assertIs<Verdict.Incapable>(verdict)
      assertIs<ExportError.UnsupportedEffect>(incapable.reasons.single()).specId shouldBe EffectIds.IMAGE_OVERLAY
    }

  private fun watermark(animation: OverlayAnimation?) =
    ImageOverlay(ImageSource.ofBytes(RED_PNG), Corner.TopStart, animation = animation)

  private suspend fun exportOf(
    landscape: String,
    overlay: ImageOverlay?,
    name: String,
  ): String {
    val composition =
      compositionOf {
        clip(MediaSource.of(landscape)) { trim(0.seconds, CLIP) }
        if (overlay != null) effects { add(overlay) }
      }
    val output = temporaryPath(name)
    exported(composition, SPEC, output)
    return output
  }

  /**
   * How far [opaque] and [ramped] each moved away from [plain] inside [rect], over one grid of
   * patches.
   *
   * Compositing over a fixed background moves each channel by the overlay's opacity times something
   * the opacity does not appear in, so the total is that opacity times what a fully opaque draw
   * moved. That only holds in light, so every reading is taken back through the transfer the file
   * was written with. A patch the opaque draw leaves alone is dropped rather than counted as encoder
   * noise, and the patches it keeps are the ones both sums run over, so a patch cannot land in one
   * and not the other.
   *
   * @return What the opaque draw moved, and what the ramped one moved.
   */
  private fun coveredBy(
    rect: NormalizedRect,
    opaque: FrameProbe,
    ramped: FrameProbe,
    plain: FrameProbe,
  ): Pair<Float, Float> {
    var whole = 0f
    var moved = 0f
    for (row in 0 until CELLS) {
      for (column in 0 until CELLS) {
        val x = rect.left + rect.width * (column + 0.5f) / CELLS
        val y = rect.top + rect.height * (row + 0.5f) / CELLS
        val base = plain.average(x, y)
        val gap = gapBetween(opaque.average(x, y), base)
        if (gap <= CELL_FLOOR) continue
        whole += gap
        moved += gapBetween(ramped.average(x, y), base)
      }
    }
    return whole to moved
  }

  private fun gapBetween(
    drawn: Triple<Int, Int, Int>,
    plain: Triple<Int, Int, Int>,
  ): Float =
    abs(light(drawn.first) - light(plain.first)) +
      abs(light(drawn.second) - light(plain.second)) +
      abs(light(drawn.third) - light(plain.third))

  /**
   * The light a code value in `0..255` stands for, through the sRGB transfer the decoded frame
   * carries.
   */
  private fun light(channel: Int): Float {
    val signal = channel / FULL
    return if (signal <= KNEE) signal / SLOPE else ((signal + OFFSET) / (1f + OFFSET)).pow(GAMMA)
  }

  private suspend fun exported(
    composition: EditComposition,
    spec: ExportSpec,
    output: String,
  ): ExportStatus.Success =
    withContext(Dispatchers.Default) {
      val statuses = filmstrip.export(composition, spec, MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> assertIs<ExportStatus.Success>(finished)
      }
    }

  private fun fixture(): String? {
    val directory = fixtures ?: return null
    val path = "$directory/apple_export_a.mp4"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val BLACK = 0xFF000000.toInt()
    val TIMEOUT = 2.seconds * 60

    val SPEC = ExportSpec(targetHeight = 180)
    val OUTPUT = Size(320, 180)
    val RASTER = Size(4, 2)
    val CLIP = 2.seconds
    val SPAN = TimeRange.of(Duration.ZERO, CLIP)
    val RAMP = 2.seconds
    val RAMP_POINTS = listOf(500.milliseconds, 1_500.milliseconds)

    const val CELLS = 8
    const val DRIFT = 0.08f
    const val CELL_FLOOR = 0.02f
    const val FLOOR = 10f

    const val FULL = 255f
    const val KNEE = 0.04045f
    const val SLOPE = 12.92f
    const val OFFSET = 0.055f
    const val GAMMA = 2.4f

    // A four by two opaque red PNG. Not square, so a watermark's height following the image's own
    // aspect is exercised.
    val RED_PNG =
      (
        "89504e470d0a1a0a0000000d4948445200000004000000020806000000" +
          "7fa87d630000001249444154789c63f8cfc0f01f1933a00b00000f210ff1" +
          "0437c69f0000000049454e44ae426082"
      ).decodeHex()

    fun String.decodeHex(): ByteArray =
      ByteArray(length / 2) { index ->
        ((this[index * 2].digitToInt(HEX) shl 4) or this[index * 2 + 1].digitToInt(HEX)).toByte()
      }

    const val HEX = 16
  }
}
