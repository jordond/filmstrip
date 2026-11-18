package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.picture.animated.ANIMATED_AVIF_REFUSED
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedPictures.Op
import dev.jordond.filmstrip.test.AnimatedWebps
import dev.jordond.filmstrip.test.PREVIEW_PARITY_EXPORT_BITRATE
import dev.jordond.filmstrip.test.assumeEncodesHdr
import dev.jordond.filmstrip.test.videoSampleTimesOf
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Animated pictures drawn by media3, checked against the pixels that were written.
 *
 * Every expected frame is the one the picture's own timeline puts on the slot, never a number worked out here, and
 * every written frame is read so the slot snap is checked as well. The counter files write the index of their frame as
 * eight black or white squares, which survive a yuv420p export. The delays are uneven and the clip outlasts one pass of
 * them, so frames are read in the middle of a cycle and across the wrap into the second play.
 *
 * Skipped when the fixtures are absent, as in [AndroidExportMediaTest].
 */
class AndroidAnimatedPictureMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun everyFrameOfAGifShowsTheFrameTheTimelineNames() =
    runTest(timeout = TIMEOUT) {
      val file = AnimatedGifs.bytes(AnimatedPictures.COUNTER_CANVAS, counters(), loops = 0)

      everyFrameShowsItsFrame(overlayOf(file), file).wraps()
    }

  @Test
  fun everyFrameOfAnApngShowsTheFrameTheTimelineNames() =
    runTest(timeout = TIMEOUT) {
      val file = AnimatedApngs.bytes(AnimatedPictures.COUNTER_CANVAS, counters())

      everyFrameShowsItsFrame(overlayOf(file), file).wraps()
    }

  @Test
  fun everyFrameOfAWebpShowsTheFrameTheTimelineNames() =
    runTest(timeout = TIMEOUT) {
      val file = AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, counters())

      everyFrameShowsItsFrame(overlayOf(file), file).wraps()
    }

  @Test
  fun aPictureThatPlaysTwiceHoldsItsLastFrame() =
    runTest(timeout = TIMEOUT) {
      val file = AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, counters(), loops = 2)

      val seen = everyFrameShowsItsFrame(overlayOf(file), file)

      seen.last() shouldBe COUNTERS - 1
    }

  // The clip runs at twice its speed, and the overlay's clock is the composition's, so the frame on a slot is the
  // timeline's for that slot of the output.
  @Test
  fun aFasterClipDoesNotSpeedThePicture() =
    runTest(timeout = TIMEOUT) {
      val file = AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, counters())

      everyFrameShowsItsFrame(overlayOf(file), file, speed = 2.0)
    }

  // The picture runs through a kept PQ grade, where the squares are read as signal rather than as sRGB. Frames are read
  // in the middle of the run, not only at its ends.
  @Test
  fun aPictureOnAKeptPqGradeShowsTheFrameTheTimelineNamesMidRun() =
    runTest(timeout = TIMEOUT) {
      probe.filmstrip.assumeEncodesHdr()
      val file = AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, counters())

      everyFrameShowsItsFrame(
        overlayOf(file),
        file,
        clip = HDR_CLIP,
        light = HDR_LIGHT,
        dark = HDR_DARK,
        every = HDR_EVERY,
      )
    }

  @Test
  fun aCanvasPastTheTextureSideIsRefusedByNameAndOneThatFitsResolves() {
    val file = AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, counters())
    val side = AnimatedPictures.COUNTER_CANVAS.width

    BuiltInEffectResolver()
      .resolve(overlayOf(file), capabilities(side), ATTRIBUTES)
      .shouldBeInstanceOf<EffectResolution.Resolved>()
    BuiltInEffectResolver()
      .resolve(overlayOf(file), capabilities(side - 1), ATTRIBUTES)
      .shouldBeInstanceOf<EffectResolution.Unsupported>()
      .message shouldContain "${side - 1} pixels on a side"
  }

  @Test
  fun aPictureWithNoFrameRateToCountOnIsRefusedByName() {
    val file = AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, counters())

    BuiltInEffectResolver()
      .resolve(overlayOf(file), capabilities(MAX_SIDE), ATTRIBUTES.withoutFrameRate())
      .shouldBeInstanceOf<EffectResolution.Unsupported>()
      .message shouldContain "frame rate"
  }

  @Test
  fun anAnimatedAvifIsRefusedByName() {
    val avis = byteArrayOf(0, 0, 0, 16) + "ftypavis".encodeToByteArray() + ByteArray(4)

    BuiltInEffectResolver()
      .resolve(overlayOf(avis), capabilities(MAX_SIDE), ATTRIBUTES)
      .shouldBeInstanceOf<EffectResolution.Unsupported>()
      .message shouldBe ANIMATED_AVIF_REFUSED
  }

  @Test
  fun aSheetOnAnAnimatedFileIsRefused() {
    val file = AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, counters())
    val sheeted =
      overlayOf(file).let {
        ImageOverlay(it.image, Corner.TopStart, margin = 0f, scale = SCALE, sheet = SpriteSheet(2, 2, 100.milliseconds))
      }

    BuiltInEffectResolver()
      .resolve(sheeted, capabilities(MAX_SIDE), ATTRIBUTES)
      .shouldBeInstanceOf<EffectResolution.Unsupported>()
  }

  private fun List<Int>.wraps() {
    withClue("the clip outlasts a cycle, so the frames wrap back to the first within it") {
      zipWithNext().any { (before, after) -> after < before } shouldBe true
    }
  }

  /**
   * Exports [spec] over [clip], reads the written frames [every] one and checks each shows the frame the timeline of
   * [file] puts on its slot, and answers the frame of each one read.
   */
  private suspend fun everyFrameShowsItsFrame(
    spec: ImageOverlay,
    file: ByteArray,
    clip: String = Media3ExportProbe.CLIP,
    speed: Double = 1.0,
    light: Int = SDR_LIGHT,
    dark: Int = SDR_DARK,
    every: Int = 1,
  ): List<Int> {
    val timeline = (animatedPictureOf(file) as AnimatedRead.Animated).picture.timeline
    val plan = probe.plan(compositionOf(spec, clip, speed), MEASURING)
    val exported = probe.export(plan)
    val rate = plan.output.frameRate
    val span = TimeRange.of(Duration.ZERO, plan.duration)
    val rect = spec.placedOn(plan.output.size, AnimatedPictures.COUNTER_CANVAS).rectOn(plan.output.size)
    val times = videoSampleTimesOf(exported)
    val seen = mutableListOf<Int>()
    withClue("the file holds frames") { times.isNotEmpty() shouldBe true }
    for ((read, time) in times.withIndex()) {
      if (read % every != 0 && read != times.lastIndex) continue
      val slot = outputSlotOf(time, rate)
      val frame = probe.frame(exported, time)
      val expected = timeline.frameOnSlot(spec.drawnRangeWithin(span).start, slot, rate)

      withClue("slot $slot at $time") { indexIn(frame, rect, light, dark) shouldBe expected }
      seen += expected
    }
    exported.delete()
    return seen
  }

  private fun compositionOf(
    spec: ImageOverlay,
    clip: String,
    speed: Double,
  ): Composition = Composition(listOf(Track(listOf(Clip(probe.fixture(clip), speed = speed)))), listOf(spec))

  private fun overlayOf(file: ByteArray): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(file),
      corner = Corner.TopStart,
      margin = 0f,
      scale = SCALE,
    )

  // Twelve counters with uneven delays, which come to 800 ms for a cycle.
  private fun counters(): List<Op> =
    AnimatedPictures.counterOps(COUNTERS).mapIndexed { index, op ->
      Op(op.x, op.y, op.tile, op.disposal, op.blend, DELAYS[index % DELAYS.size])
    }

  // The index the eight squares of the counter write, read at the middle of each square and snapped to black or white.
  // The chroma of a yuv420p frame is shared by two pixels, so a square is told apart by its brightness, a texel whose
  // three channels average above [light] being white and one under [dark] black.
  private fun indexIn(
    frame: Bitmap,
    rect: NormalizedRect,
    light: Int,
    dark: Int,
  ): Int? {
    val canvas = AnimatedPictures.COUNTER_CANVAS
    var index = 0
    for (bit in 0 until SQUARES) {
      val x = rect.left * frame.width + (SQUARE_LEFT + SQUARE_STEP * bit + 1f) * rect.width * frame.width / canvas.width
      val y = rect.top * frame.height + (SQUARE_ROW + 1f) * rect.height * frame.height / canvas.height
      val argb = frame.getPixel(x.toInt().coerceIn(0, frame.width - 1), y.toInt().coerceIn(0, frame.height - 1))
      val level = ((argb shr RED_SHIFT and BYTE) + (argb shr GREEN_SHIFT and BYTE) + (argb and BYTE)) / 3
      index =
        when {
          level > light -> (index shl 1) or 1
          level < dark -> index shl 1
          else -> return null
        }
    }
    return index
  }

  private fun capabilities(maxTextureSize: Int): RenderCapabilities =
    RenderCapabilities(
      api = RenderApi.OpenGlEs,
      supportsFragmentShader = true,
      supportsComputeShader = false,
      supportsHdr = false,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = maxTextureSize,
      features = emptySet(),
    )

  private fun Attributes.withoutFrameRate(): Attributes =
    Attributes(
      inputSize = inputSize,
      outputSize = outputSize,
      layoutSize = layoutSize,
      colorSpace = colorSpace,
      hdrTransfer = hdrTransfer,
      frameRate = null,
      span = span,
    )

  private companion object {
    // The output the other exports make, at the bitrate a pixel read is measured at so the encode moves a flat cell
    // less.
    val MEASURING = ExportSpec(heightPx = 240, bitrate = PREVIEW_PARITY_EXPORT_BITRATE)
    const val SCALE = 0.5f
    const val MAX_SIDE = 4096
    const val COUNTERS = 12
    val DELAYS = listOf(30, 70, 40, 120, 50, 90)

    // What a white and a black square read on an sRGB frame and on a PQ frame, whose white is a signal of about 0.58.
    const val SDR_LIGHT = 160
    const val SDR_DARK = 96
    const val HDR_LIGHT = 90
    const val HDR_DARK = 40
    const val HDR_EVERY = 3

    const val SQUARES = 8
    const val SQUARE_LEFT = 2
    const val SQUARE_STEP = 3
    const val SQUARE_ROW = 7
    const val BYTE = 0xFF
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8

    val ATTRIBUTES =
      Attributes(
        inputSize = Size(320, 180),
        outputSize = Size(320, 180),
        layoutSize = Size(320, 180),
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 2_000.milliseconds),
      )
  }
}
