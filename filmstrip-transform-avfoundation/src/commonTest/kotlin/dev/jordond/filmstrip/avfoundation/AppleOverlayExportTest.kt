package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.animatedBy
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
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
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
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
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
 * The fade reads the written file back, since a property that changes per frame is one nothing
 * upstream of the encoder can prove arrived. So do the letterbox cases, which check an overlay draws
 * over a solid fill's bars and gaps and that a clip overlay stays on its clip.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleOverlayExportTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  private val filmstrip = Filmstrip { avFoundationBackend() }

  // The fill lands in the buffer as its own codes, so this is how the probe reads it back.
  private val tealDrawn = TEAL.asBufferCodes().inProbeCodes()

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

  // A composition overlay is placed on the output frame, so one authored into a bar draws there and
  // the fill keeps its own colour everywhere else.
  @Test
  fun `draws a composition overlay over a solid bar`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture(PORTRAIT) ?: return@runTest
      val badge = ImageOverlay(ImageSource.ofBytes(RED_PNG), Corner.TopStart)

      val box = letterboxed(portrait, "bar-overlay", overlay = badge)
      val rect = badge.placedOn(box.size, RASTER).rectOn(box.size)
      assertTrue(rect.right < box.bar, "the badge reaches ${rect.right}, past the bar at ${box.bar}")

      val drawn = frameOf(box.path, MIDDLE)
      assertClose(drawn.average(rect.middleX, rect.middleY), RED, "the badge in the bar")
      assertClose(drawn.average(rect.middleX, (rect.bottom + 1f) / 2f), tealDrawn, "the bar under the badge")
      assertClose(drawn.average(1f - box.bar / 2f, 0.5f), tealDrawn, "the far bar")
      remove(box.path)
    }

  // An SDR overlay blends on the buffer's own codes on every backend, and both colours land there as
  // authored, so where a partial opacity lands is predicted from the two colours' own codes. Red's
  // green and blue sit at zero, where sRGB's curve and BT.709's part company, so the case also says
  // which of the two the blend ran on.
  @Test
  fun `blends a half opaque composition overlay with the bar under it`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture(PORTRAIT) ?: return@runTest
      assertBlendsWithTheBar(portrait, HALF, "bar-overlay-half")
    }

  @Test
  fun `blends a quarter opaque composition overlay with the bar under it`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture(PORTRAIT) ?: return@runTest
      assertBlendsWithTheBar(portrait, QUARTER, "bar-overlay-quarter")
    }

  @Test
  fun `draws a composition overlay over the gap before a late primary`() =
    runTest(timeout = TIMEOUT) {
      assertDrawsOverTheGap(cropped = true, "gap-overlay")
    }

  // No geometry effect runs here, so nothing but the fill stands in for the picture the gap lacks.
  @Test
  fun `draws a composition overlay over the gap before a late primary with no geometry effect`() =
    runTest(timeout = TIMEOUT) {
      assertDrawsOverTheGap(cropped = false, "gap-overlay-uncropped")
    }

  // The gap cases need the portrait fixture, so a missing one fails them rather than passing them.
  private suspend fun assertDrawsOverTheGap(
    cropped: Boolean,
    name: String,
  ) {
    val portrait = requiredFixture(PORTRAIT)
    val badge = ImageOverlay(ImageSource.ofBytes(RED_PNG), Corner.TopStart)

    val box = letterboxed(portrait, name, overlay = badge, start = GAP, cropped = cropped)
    val rect = badge.placedOn(box.size, RASTER).rectOn(box.size)

    val drawn = frameOf(box.path, GAP / 2)
    assertClose(drawn.average(rect.middleX, rect.middleY), RED, "the badge over the gap")
    assertClose(drawn.average(0.5f, 0.5f), tealDrawn, "the gap where the picture will be")
    remove(box.path)
  }

  // Half the badge is pushed off the clip's own frame, so that half must not land in the bar beside
  // it. The half still on the clip is read too, or a badge that never drew would pass.
  @Test
  fun `cuts a clip overlay pushed off its clip at the clip's edge`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture(PORTRAIT) ?: return@runTest
      val source = frameOf(portrait)
      val clipFrame = Size(source.width, source.height)
      val authored = ImageOverlay(ImageSource.ofBytes(RED_PNG), Corner.TopStart, scale = CLIP_BADGE_SCALE)
      val placed = authored.placedOn(clipFrame, RASTER)
      val unmoved = placed.rectOn(clipFrame)
      val push = OverlayFrame(offset = OverlayOffset(-(unmoved.left + unmoved.width / 2f), 0f))
      val badge =
        ImageOverlay(authored.image, authored.corner, scale = authored.scale, animation = OverlayAnimation { push })
      val rect = placed.animatedBy(push).rectOn(clipFrame)
      assertTrue(rect.left < 0f && rect.right > 0f, "the badge at $rect does not straddle the clip's edge")

      val box = letterboxed(portrait, "clip-overlay-off", clipOverlay = badge)

      // The picture spans the output's full height, so a clip's y is the output's y and only x moves.
      val drawn = frameOf(box.path, MIDDLE)
      // Read halfway between the clip's left edge and the badge's right edge.
      assertClose(drawn.average(box.bar + rect.right * box.picture / 2f, rect.middleY), RED, "the badge on the clip")
      assertClose(drawn.average(box.bar + rect.left * box.picture / 2f, rect.middleY), tealDrawn, "the bar beside it")
      remove(box.path)
    }

  /**
   * Exports [portrait] letterboxed into a landscape frame over a solid fill, with the overlays given.
   *
   * [overlay] goes on the composition and [clipOverlay] on the clip. The clip opens at [start], which
   * leaves a gap before it when that is past zero. With [cropped] false no crop runs, so the output
   * keeps the clip's own shape and has no bars.
   */
  private suspend fun letterboxed(
    portrait: String,
    name: String,
    overlay: ImageOverlay? = null,
    clipOverlay: ImageOverlay? = null,
    start: Duration = Duration.ZERO,
    cropped: Boolean = true,
  ): Letterbox {
    val composition =
      compositionOf {
        track {
          clip(MediaSource.of(portrait)) {
            trim(Duration.ZERO, LETTERBOX_CLIP)
            if (clipOverlay != null) effects { add(clipOverlay) }
          }
          startAt(start)
        }
        fill(Fill.Solid(TEAL.toArgb()))
        effects {
          if (cropped) crop(AspectRatio.Landscape, Fit.Contain)
          if (overlay != null) add(overlay)
        }
      }
    val output = temporaryPath(name)
    val size = assertNotNull(exported(composition, SPEC, output).info.video).displaySize
    val source = frameOf(portrait)
    return Letterbox(output, size, source.width.toFloat() / source.height * size.height / size.width)
  }

  /**
   * A letterboxed export, where [picture] is the share of the output's width the clip covers.
   */
  private class Letterbox(
    val path: String,
    val size: Size,
    val picture: Float,
  ) {
    val bar: Float get() = (1f - picture) / 2f
  }

  private suspend fun assertBlendsWithTheBar(
    portrait: String,
    opacity: Float,
    name: String,
  ) {
    val badge = ImageOverlay(ImageSource.ofBytes(RED_PNG), Corner.TopStart, opacity = opacity)
    val box = letterboxed(portrait, name, overlay = badge)
    val rect = badge.placedOn(box.size, RASTER).rectOn(box.size)

    val inCodes = TEAL.asBufferCodes().towards(RED.asBufferCodes(), badge.opacity).inProbeCodes()
    val inSrgb = TEAL.towards(RED, badge.opacity)
    val gap = distance(inCodes, inSrgb)
    val drawn = frameOf(box.path, MIDDLE).average(rect.middleX, rect.middleY)
    remove(box.path)

    assertTrue(gap > 0, "at $opacity the buffer's codes and sRGB's both ask for $inCodes, so nothing tells them apart")
    assertTrue(
      distance(drawn, inCodes) * 2 < gap,
      "the badge at $opacity drew $drawn, where the buffer's codes ask for $inCodes and sRGB's for $inSrgb",
    )
  }

  private val NormalizedRect.middleX: Float get() = (left + right) / 2f

  private val NormalizedRect.middleY: Float get() = (top + bottom) / 2f

  /**
   * The colour a source over draw of [overlay] at [opacity] leaves on this one, worked on sRGB's code
   * values in `0..255` rather than on the buffer's own.
   */
  private fun Triple<Int, Int, Int>.towards(
    overlay: Triple<Int, Int, Int>,
    opacity: Float,
  ): Triple<Int, Int, Int> {
    fun channel(
      under: Int,
      over: Int,
    ): Int = (under + (over - under) * opacity).roundToInt()
    return Triple(channel(first, overlay.first), channel(second, overlay.second), channel(third, overlay.third))
  }

  /**
   * The buffer codes a source over draw of [overlay] at [opacity] leaves on these, channel by channel.
   */
  private fun FloatArray.towards(
    overlay: FloatArray,
    opacity: Float,
  ): FloatArray = FloatArray(size) { this[it] + (overlay[it] - this[it]) * opacity }

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
   * moved. That holds in the buffer's own codes an SDR overlay blends on, so every reading is carried
   * back into the codes the file was written with. A patch the opaque draw leaves alone is dropped rather than
   * counted as encoder noise, and the patches it keeps are the ones both sums run over, so a patch
   * cannot land in one and not the other.
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
  ): Float {
    val over = drawn.inBufferCodes()
    val under = plain.inBufferCodes()
    return over.indices.sumOf { abs(over[it] - under[it]).toDouble() }.toFloat()
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

  private fun fixture(name: String = LANDSCAPE): String? {
    val directory = fixtures ?: return null
    val path = "$directory/$name"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private fun requiredFixture(name: String): String =
    fixture(name) ?: fail("$name is not in $FIXTURES, which the Gradle test task builds and points at")

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val LANDSCAPE = "apple_export_a.mp4"
    const val PORTRAIT = "apple_export_portrait.mp4"
    const val BLACK = 0xFF000000.toInt()
    val TIMEOUT = 2.seconds * 60

    val SPEC = ExportSpec(targetHeight = 180)
    val OUTPUT = Size(320, 180)
    val RASTER = Size(4, 2)
    val CLIP = 2.seconds
    val SPAN = TimeRange.of(Duration.ZERO, CLIP)
    val RAMP = 2.seconds
    val RAMP_POINTS = listOf(500.milliseconds, 1_500.milliseconds)
    val LETTERBOX_CLIP = 1.seconds
    val MIDDLE = LETTERBOX_CLIP / 2
    val GAP = 1.seconds
    const val HALF = 0.5f
    const val QUARTER = 0.25f
    const val CLIP_BADGE_SCALE = 0.5f

    // The fill the letterbox cases paint.
    val TEAL = Triple(0x11, 0xC2, 0xAA)

    const val CELLS = 8
    const val DRIFT = 0.08f
    const val CELL_FLOOR = 0.02f
    const val FLOOR = 10f

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

// The colour the overlay export tests draw their badges in.
internal val RED = Triple(0xFF, 0x00, 0x00)
