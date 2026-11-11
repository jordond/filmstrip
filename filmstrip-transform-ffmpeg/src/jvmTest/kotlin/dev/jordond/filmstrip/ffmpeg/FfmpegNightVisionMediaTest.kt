package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.nightvision.NightVisionParams
import dev.jordond.filmstrip.effects.filters.nightvision.NightVisionSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.BEFORE_ZERO
import dev.jordond.filmstrip.ffmpeg.support.BEFORE_ZERO_SECONDS
import dev.jordond.filmstrip.ffmpeg.support.FILM_CHROMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.FILM_LUMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_SPAN
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_ZERO
import dev.jordond.filmstrip.ffmpeg.support.VHS_KB
import dev.jordond.filmstrip.ffmpeg.support.VHS_KR
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_BLACK
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_SPAN
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import dev.jordond.filmstrip.ffmpeg.support.fragmentFrame
import dev.jordond.filmstrip.ffmpeg.support.fragmentOf
import dev.jordond.filmstrip.ffmpeg.support.graySource
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.planeCodesOf
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the frames ffmpeg draws for a [NightVision] straight off the preview pump against [NightVisionSignal], with no
 * encoder in the way, and what it keeps at each scope.
 *
 * The picture is a ramp across the frame, which a lossless clip holds exactly. Luma is read pixel by pixel, and chroma
 * against the pixels swscale weighs into a 4:2:0 sample, since the grain moves every pixel's colour on its own. Both
 * hold to film's limits, since the look runs the same round trip through RGB.
 */
class FfmpegNightVisionMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a composition look lands on the signal at half intensity`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      assertLanded(
        draw.planesAt(draw.composition(listOf(clip), listOf(HALF)), READ_SLOT),
        draw.planesAt(draw.composition(listOf(clip)), 0),
      )
    }

  @Test
  fun `a clip look lands on the signal at half intensity`() =
    runBlocking<Unit> {
      val looked = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(HALF))
      val plain = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      assertLanded(
        draw.planesAt(draw.composition(listOf(looked)), READ_SLOT),
        draw.planesAt(draw.composition(listOf(plain)), 0),
      )
    }

  // Mid range rather than half, where a mix read as kept plus intensity and one read as look plus its remainder agree.
  @Test
  fun `a clip look lands on the signal at a mid intensity`() =
    runBlocking<Unit> {
      val looked = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(MID))
      val plain = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      assertLanded(
        draw.planesAt(draw.composition(listOf(looked)), READ_SLOT),
        draw.planesAt(draw.composition(listOf(plain)), 0),
        MID,
      )
    }

  @Test
  fun `the live and drawn grain draw the same frames at a mid intensity`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(MID))))
      val drawn = draw.formPlanes(plan, FORM_FRAMES, live = false)
      val planes = draw.formPlanes(plan, FORM_FRAMES, live = true)

      val differing = drawn.indices.count { drawn[it] != planes[it] }
      withClue("$differing of ${drawn.size} samples differ") { differing shouldBe 0 }
    }

  // swscale widens a code to about 256 times itself, so a read over 257 takes the kept frame a code low from 129 up.
  // At a low intensity the kept frame is most of the read, which would sit that much under the signal on average.
  @Test
  fun `a frame that lands before zero still reads the vignette map`() {
    val frame = Size(WIDTH, HEIGHT)
    val source = graySource(frame, "$GRAY_LUMA")
    val drawn = fragmentFrame(source + BEFORE_ZERO, frame, fragmentOf(FilterEffectResolver(), HALF, frame))

    assertLanded(drawn, fragmentFrame(source, frame), tick = FilmSignal.grainTickAt(BEFORE_ZERO_SECONDS))
  }

  @Test
  fun `a light picture under a faint look lands on the signal on average`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::lightRampAt)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(FAINT)), READ_SLOT)
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0)
      val wanted = wantedOf(plain, FAINT)
      val misses = reads(HEIGHT).flatMap { y -> reads(WIDTH).map { x -> frame.luma(x, y) - wanted(x, y)[0] } }

      withClue("the drawn luma sits ${misses.average()} codes off the signal on average") {
        abs(misses.average()) shouldBeLessThan BIAS_CODES
      }
    }

  @Test
  fun `a smaller preview draws the same look at its own size`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val spec = ExportSpec(heightPx = HEIGHT / 2)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(HALF)), READ_SLOT, spec)

      frame.height shouldBe HEIGHT / 2
      assertLanded(frame, draw.planesAt(draw.composition(listOf(clip)), 0, spec))
    }

  @Test
  fun `a preview at three quarters draws the same look at its own size`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val spec = ExportSpec(heightPx = HEIGHT * 3 / 4)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(HALF)), READ_SLOT, spec)

      frame.height shouldBe HEIGHT * 3 / 4
      assertLanded(frame, draw.planesAt(draw.composition(listOf(clip)), 0, spec))
    }

  // A 4K frame's grain cells are a whole number of pixels, so the map is drawn on the cells and widened.
  @Test
  fun `a 4K look lands on the signal`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(UHD_WIDTH, UHD_HEIGHT, ::rampAt, seconds = 1)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(HALF)), READ_SLOT)

      frame.width shouldBe UHD_WIDTH
      assertLanded(frame, draw.planesAt(draw.composition(listOf(clip)), 0))
    }

  @Test
  fun `an overlay at the same scope stays clean`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val x = (SWATCH_X * WIDTH).toInt()
      val y = (SWATCH_Y * HEIGHT).toInt()

      val looked = draw.planesAt(draw.composition(listOf(clip), listOf(swatch(), HALF)), READ_SLOT)
      val plain = draw.planesAt(draw.composition(listOf(clip), listOf(swatch())), READ_SLOT)

      val bare = draw.planesAt(draw.composition(listOf(clip)), 0).luma(x, y)
      val lookedBare = draw.planesAt(draw.composition(listOf(clip), listOf(HALF)), READ_SLOT).luma(x, y)
      withClue("the look has to move the pixel under the swatch for this to tell") {
        abs(lookedBare - bare).toDouble() shouldBeGreaterThan EFFECT_CODES
      }
      looked.luma(x, y) shouldBe plain.luma(x, y)
      looked.cb(x / 2, y / 2) shouldBe plain.cb(x / 2, y / 2)
      looked.cr(x / 2, y / 2) shouldBe plain.cr(x / 2, y / 2)
    }

  // Coded at half the width, so maps of the display size have to be met by a picture of the display size.
  @Test
  fun `a clip look on non-square pixels lands on the signal at the display size`() =
    runBlocking<Unit> {
      val frame = draw.planesAt(draw.composition(listOf(anamorphicClip(HALF))), READ_SLOT)

      frame.width shouldBe WIDTH
      assertLanded(frame, draw.planesAt(draw.composition(listOf(anamorphicClip())), 0))
    }

  @Test
  fun `the live grain plane draws the same samples as the drawn map`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(HALF))))
      val drawn = draw.formPlanes(plan, FORM_FRAMES, live = false)
      val planes = draw.formPlanes(plan, FORM_FRAMES, live = true)

      val differing = drawn.indices.count { drawn[it] != planes[it] }
      withClue("$differing of ${drawn.size} samples differ") { differing shouldBe 0 }
      val frameBytes = drawn.size / FORM_FRAMES
      withClue("the grain moves between the first frame and the last") {
        drawn.copyOfRange(0, frameBytes).contentEquals(drawn.copyOfRange(drawn.size - frameBytes, drawn.size)) shouldBe
          false
      }
    }

  @Test
  fun `a frame from a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::rampAt)), listOf(HALF))
      val at = (READ_SLOT.toDouble() / FILTER_RATE).seconds

      val fromStart = draw.frameAt(composition, Duration.ZERO, READ_SLOT)
      val fromWindow = draw.frames(composition, at, 1).single()

      fromWindow.contentEquals(fromStart) shouldBe true
    }

  @Test
  fun `a clip look after a contain crop keeps its alpha and leaves the bar as the fill`() =
    runBlocking<Unit> {
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(crop, NightVision()))
      val frame =
        draw
          .frameAt(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)), Duration.ZERO, READ_SLOT)

      val barRow = HEIGHT / BAR_READ
      (0 until 3).forEach { channel ->
        withClue("channel $channel") {
          frame.channelAt(HEIGHT, HEIGHT / 2, barRow, channel).toDouble() shouldBe
            (SLATE_CODES[channel] plusOrMinus BAR_CODES)
        }
      }
    }

  private fun assertLanded(
    frame: Yuv420Frame,
    plain: Yuv420Frame,
    look: NightVision = HALF,
    tick: Int = READ_TICK,
  ) {
    val width = frame.width
    val height = frame.height
    val wanted = wantedOf(plain, look, tick)
    var lumaMiss = 0.0
    var chromaMiss = 0.0
    var moved = 0.0
    reads(height).forEach { y ->
      reads(width).forEach { x ->
        lumaMiss = max(lumaMiss, abs(frame.luma(x, y) - wanted(x, y)[0]))
        moved = max(moved, abs(frame.luma(x, y) - plain.luma(x, y).toDouble()))
        val footprint =
          CHROMA_ROWS.flatMap { (dy, weight) ->
            val row = (y / 2 * 2 + dy).coerceIn(0, height - 1)
            (0..1).map { dx -> weight / 2 to wanted(x / 2 * 2 + dx, row) }
          }
        chromaMiss = max(chromaMiss, abs(frame.cb(x / 2, y / 2) - footprint.sumOf { (w, codes) -> w * codes[1] }))
        chromaMiss = max(chromaMiss, abs(frame.cr(x / 2, y / 2) - footprint.sumOf { (w, codes) -> w * codes[2] }))
      }
    }
    withClue("the look has to move the frame well past the tolerance for this to tell") {
      moved shouldBeGreaterThan EFFECT_CODES
    }
    withClue("luma of $width by $height") { lumaMiss shouldBeLessThan FILM_LUMA_CODES }
    withClue("chroma of $width by $height") { chromaMiss shouldBeLessThan FILM_CHROMA_CODES }
  }

  // The codes [NightVisionSignal.pixelAt] puts [look] at for each pixel of [plain] at [tick].
  private fun wantedOf(
    plain: Yuv420Frame,
    look: NightVision,
    tick: Int = READ_TICK,
  ): (Int, Int) -> DoubleArray {
    val params = NightVisionParams.of(look)
    return { x, y ->
      val codes = plain.rgbAt(x, y)
      planeCodesOf(
        NightVisionSignal.pixelAt(codes[0], codes[1], codes[2], x, y, plain.width, plain.height, tick, params),
      )
    }
  }

  // Even rows and columns spread over the frame, so each read's chroma block sits inside it.
  private fun reads(size: Int): List<Int> = ((0 until size - 1 step READ_STRIDE) + (size / 2) + (size - 2)).distinct()

  // An exact BT.709 decode with no clip and no rounding.
  private fun Yuv420Frame.rgbAt(
    x: Int,
    y: Int,
  ): DoubleArray {
    val luma = (luma(x, y) - VHS_LUMA_BLACK) / VHS_LUMA_SPAN
    val red = luma + 2.0 * (1.0 - VHS_KR) * (cr(x / 2, y / 2) - VHS_CHROMA_ZERO) / VHS_CHROMA_SPAN
    val blue = luma + 2.0 * (1.0 - VHS_KB) * (cb(x / 2, y / 2) - VHS_CHROMA_ZERO) / VHS_CHROMA_SPAN
    return doubleArrayOf(red, (luma - VHS_KR * red - VHS_KB * blue) / (1.0 - VHS_KR - VHS_KB), blue)
  }

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), PALE_SWATCH_ARGB)),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  // A luma and Cb ramp down a BT.709 picture stored half as wide as it shows, with pixels twice as wide as they are
  // tall, all of it inside the RGB gamut. Each row is one code, so widening it lands on the same codes whichever way it
  // scales.
  private fun anamorphicClip(vararg effects: EffectSpec): Clip {
    val stored = File.createTempFile("nightvision-anamorphic", ".mkv").apply { deleteOnExit() }
    val encode =
      ProcessBuilder(
        hostFfmpeg,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "color=c=black:s=${WIDTH / 2}x$HEIGHT:r=$FILTER_RATE:d=2,format=yuv420p," +
          "geq=lum='60+Y/3':cb='118+Y/18':cr=136,setsar=2",
        "-colorspace",
        "bt709",
        "-color_range",
        "tv",
        "-c:v",
        "ffv1",
        "-pix_fmt",
        "yuv420p",
        stored.path,
      ).inheritIO().start()
    encode.waitFor() shouldBe 0
    return Clip(MediaSource.of(stored.path), effects = effects.toList())
  }

  // A column's codes, from the fraction of the width it sits at, all of it inside the RGB gamut.
  private fun rampAt(x: Double): DoubleArray = doubleArrayOf(0.2 + 0.6 * x, 0.45, 0.6 - 0.4 * x)

  // A ramp of every channel through the codes from 129 up, so rounding lands as far over as under across a row.
  private fun lightRampAt(x: Double): DoubleArray = doubleArrayOf(0.55 + 0.4 * x, 0.6 + 0.3 * x, 0.9 - 0.35 * x)

  private companion object {
    val HALF = NightVision(intensity = 0.5f, seed = 7)
    val FAINT = NightVision(intensity = 0.2f, seed = 7)
    val MID = NightVision(intensity = 0.37f, seed = 7)

    // Rounding lands as far over as under, so a frame's reads average out well inside this.
    const val BIAS_CODES = 0.3
    const val GRAY_LUMA = 126
    const val WIDTH = 640
    const val HEIGHT = 360
    const val UHD_WIDTH = 3840
    const val UHD_HEIGHT = 2160
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val PALE_SWATCH_ARGB = 0xFFE0E0E0.toInt()
    const val SWATCH_X = 0.15
    const val SWATCH_Y = 0.2
    const val READ_SLOT = 12
    val READ_TICK = FilmSignal.grainTickAt(READ_SLOT.toDouble() / FILTER_RATE)
    const val FORM_FRAMES = 12
    const val READ_STRIDE = 22
    const val BAR_READ = 16
    const val BAR_CODES = 3.0
    const val SLATE = 0xFF2F4F6F.toInt()
    val SLATE_CODES = listOf(0x2F.toDouble(), 0x4F.toDouble(), 0x6F.toDouble())

    // swscale's step down to 4:2:0 weighs the two columns a chroma sample covers evenly, and four rows 1/8, 3/8,
    // 3/8 and 1/8 from the row above its own two, measured on ffmpeg 9.0.2. Read against it and film's limits, the
    // look lands within 1.34 codes in luma and 0.78 in chroma on ffmpeg 8.0.1 x86_64.
    val CHROMA_ROWS = listOf(-1 to 0.125, 0 to 0.375, 1 to 0.375, 2 to 0.125)

    // Well past the tolerances, so a read landing inside them says the look is there.
    const val EFFECT_CODES = 6.0
  }
}
