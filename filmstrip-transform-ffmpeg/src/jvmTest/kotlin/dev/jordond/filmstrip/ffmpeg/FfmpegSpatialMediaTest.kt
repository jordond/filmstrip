package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.color.nitsFromPqSignal
import dev.jordond.filmstrip.color.nitsFromSdrSignal
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.color.vignetteGain
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.spatial.GaussianBlur
import dev.jordond.filmstrip.effects.spatial.Vignette
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.BEFORE_ZERO
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_HDR_SPEC
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.clipOfPicture
import dev.jordond.filmstrip.ffmpeg.support.fragmentFrame
import dev.jordond.filmstrip.ffmpeg.support.fragmentOf
import dev.jordond.filmstrip.ffmpeg.support.graySource
import dev.jordond.filmstrip.ffmpeg.support.lumaClipOf
import dev.jordond.filmstrip.ffmpeg.support.pqClipOfLuma
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the frames ffmpeg draws for a [GaussianBlur] and a [Vignette] straight off the preview pump, each against the
 * shared function it lowers, and where each lands.
 *
 * The picture is flat grey, which a lossless clip holds exactly, so a luma code follows red, green and blue and a blur
 * of the luma plane by [blurPlane] is the blur of each colour.
 */
class FfmpegSpatialMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a blur at export size lands within the way through rgb of the shared plan`() =
    runBlocking<Unit> { assertBlurMatches(draw.clipOf(WIDTH, HEIGHT, ::edgeAt), ExportSpec(), WIDTH) }

  @Test
  fun `a blur at preview size lands within the way through rgb of the shared plan`() =
    runBlocking<Unit> {
      assertBlurMatches(
        draw.clipOf(WIDTH, HEIGHT, ::edgeAt),
        ExportSpec(heightPx = HEIGHT / 2),
        WIDTH / 2,
      )
    }

  @Test
  fun `a blur on a width the plan does not divide lands within the way through rgb of the shared plan`() =
    runBlocking<Unit> {
      val plan = BlurPlan.of(SIGMA.toDouble(), UNEVEN_WIDTH)
      withClue("the plan boxes by ${plan.factor}, which divides $UNEVEN_WIDTH") {
        (UNEVEN_WIDTH % plan.factor == 0) shouldBe false
      }
      assertBlurMatches(draw.clipOf(UNEVEN_WIDTH, HEIGHT, ::edgeAt), ExportSpec(), UNEVEN_WIDTH)
    }

  @Test
  fun `a blur across a horizontal edge lands within the way through rgb of the vertical pass`() =
    runBlocking<Unit> {
      val clip = draw.clipOfPicture(WIDTH, HEIGHT, { _, y -> edgeAt(y) })
      assertBlurMatches(clip, ExportSpec(), WIDTH)
    }

  @Test
  fun `a blur at a smaller sigma lands on the shared plan with its own taps`() =
    runBlocking<Unit> {
      val plan = BlurPlan.of(SMALL_SIGMA.toDouble(), WIDTH)
      withClue("a smaller sigma has to reach fewer taps than the first one") {
        plan.taps shouldBeLessThan BlurPlan.of(SIGMA.toDouble(), WIDTH).taps
      }
      assertBlurMatches(draw.clipOf(WIDTH, HEIGHT, ::edgeAt), ExportSpec(), WIDTH, SMALL_SIGMA, SMALL_SPREAD)
    }

  @Test
  fun `a vignette follows the shared gain along its ramp`() =
    runBlocking<Unit> {
      val vignette = Vignette()
      val clip = draw.clipOf(WIDTH, HEIGHT, { DoubleArray(3) { GREY } })
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0)
      val drawn = draw.planesAt(draw.composition(listOf(clip), listOf(vignette)), 0)

      assertVignetted(drawn, plain, vignette)
    }

  @Test
  fun `a frame that lands before zero still reads the vignette map`() {
    val vignette = Vignette()
    val frame = Size(WIDTH, HEIGHT)
    val source = graySource(frame, "$GREY_LUMA")
    val drawn = fragmentFrame(source + BEFORE_ZERO, frame, fragmentOf(BuiltInEffectResolver(), vignette, frame))

    assertVignetted(drawn, fragmentFrame(source, frame), vignette)
  }

  // swscale widens a code to about 256 times itself, so a read over 257 at a gain of 1 takes a code from 129 up one
  // low. A grey's luma steps less than its red, green and blue, so the ramp holds every luma from that code up, and
  // some of them land a luma code low too.
  @Test
  fun `a vignette at a gain of 1 keeps every light grey on its code at either scope`() =
    runBlocking<Unit> {
      val vignette = Vignette()
      val y = HEIGHT / 2
      val untouched = (0 until WIDTH).filter { vignetteGainAt(it, y, vignette) == 1.0 }
      val ramp = { x: Int -> LIGHT_LUMA_LOW + x % (LIGHT_LUMA_HIGH - LIGHT_LUMA_LOW + 1) }
      val clip = { effects: List<EffectSpec> ->
        draw.clipOfPlanes(WIDTH, HEIGHT, ramp, { NEUTRAL }, { NEUTRAL }, effects)
      }
      val plain = draw.planesAt(draw.composition(listOf(clip(emptyList()))), 0)
      withClue("a gain of 1 has to hold every luma of the ramp for this to tell") {
        untouched.map { plain.luma(it, y) }.toSet() shouldBe (LIGHT_LUMA_LOW..LIGHT_LUMA_HIGH).toSet()
      }
      val scopes =
        mapOf(
          "the composition" to draw.planesAt(draw.composition(listOf(clip(emptyList())), listOf(vignette)), 0),
          "its clip" to draw.planesAt(draw.composition(listOf(clip(listOf(vignette)))), 0),
        )

      for ((scope, drawn) in scopes) {
        for (x in untouched) {
          withClue("luma at $x on $scope") { drawn.luma(x, y) shouldBe plain.luma(x, y) }
        }
      }
    }

  @Test
  fun `a clip blur lands on its clip and not on the clip joined after it`() =
    runBlocking<Unit> {
      val blurred = draw.clipOf(WIDTH, HEIGHT, ::edgeAt, listOf(GaussianBlur(SIGMA)))
      val sharp = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      val joined = draw.composition(listOf(blurred, sharp))
      val plain = draw.planesAt(draw.composition(listOf(sharp)), 0)

      assertBlurred(draw.planesAt(joined, 0), plain, DoubleArray(WIDTH * HEIGHT) { 1.0 })
      val next = draw.planesAt(joined, FILTER_RATE * CLIP_SECONDS + 1)
      (0 until WIDTH).forEach { column ->
        withClue("column $column of the clip after the blurred one") {
          next.luma(column, HEIGHT / 2) shouldBe plain.luma(column, HEIGHT / 2)
        }
      }
    }

  @Test
  fun `a composition blur keeps a contained picture to its edge and leaves the bars exactly the fill`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      val plain = draw.planesAt(draw.composition(listOf(clip), listOf(contain()), Fill.Solid(SLATE)), 0)
      val blurred =
        draw.planesAt(draw.composition(listOf(clip), listOf(contain(), GaussianBlur(SIGMA)), Fill.Solid(SLATE)), 0)

      assertContained(blurred, plain)
    }

  @Test
  fun `a clip blur after a contain crop keeps the picture to its edge and leaves the bars exactly the fill`() =
    runBlocking<Unit> {
      val plain = draw.planesAt(draw.composition(listOf(containedClip()), fill = Fill.Solid(SLATE)), 0)
      val blurred =
        draw.planesAt(draw.composition(listOf(containedClip(GaussianBlur(SIGMA))), fill = Fill.Solid(SLATE)), 0)

      assertContained(blurred, plain)
    }

  @Test
  fun `a clip blur on non-square pixels lands on the shared plan at the display size`() =
    runBlocking<Unit> {
      val plain = draw.planesAt(draw.composition(listOf(anamorphicClip(DARK_LUMA))), 0)
      val blurred = draw.planesAt(draw.composition(listOf(anamorphicClip(DARK_LUMA, GaussianBlur(SIGMA)))), 0)

      blurred.width shouldBe WIDTH
      assertBlurred(blurred, plain, DoubleArray(WIDTH * HEIGHT) { 1.0 })
    }

  @Test
  fun `a clip vignette on non-square pixels follows the shared gain at the display size`() =
    runBlocking<Unit> {
      val vignette = Vignette()
      val plain = draw.planesAt(draw.composition(listOf(anamorphicClip(GREY_LUMA))), 0)
      val drawn = draw.planesAt(draw.composition(listOf(anamorphicClip(GREY_LUMA, vignette))), 0)

      drawn.width shouldBe WIDTH
      assertVignetted(drawn, plain, vignette)
    }

  @Test
  fun `a frame from a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      listOf(GaussianBlur(SIGMA), Vignette()).forEach { spatial ->
        val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::edgeAt)), listOf(spatial))
        val fromStart = draw.frameAt(composition, Duration.ZERO, READ_SLOT)
        val fromWindow = draw.frames(composition, (READ_SLOT.toDouble() / FILTER_RATE).seconds, 1).single()

        withClue(spatial) { fromWindow.contentEquals(fromStart) shouldBe true }
      }
    }

  @Test
  fun `a spatial effect on every clip stays on each clip`() =
    runBlocking<Unit> {
      listOf<EffectSpec>(GaussianBlur(SIGMA), Vignette()).forEach { spatial ->
        val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt, listOf(spatial))
        withClue(spatial) { draw.graphOf(draw.composition(listOf(clip, clip))) shouldNotContain HOISTED }
      }
    }

  private suspend fun assertBlurMatches(
    clip: Clip,
    spec: ExportSpec,
    width: Int,
    sigma: Float = SIGMA,
    minSpread: Double = SHARP_SPREAD,
  ) {
    val blurred = draw.planesAt(draw.composition(listOf(clip), listOf(GaussianBlur(sigma))), 0, spec)
    blurred.width shouldBe width
    val plain = draw.planesAt(draw.composition(listOf(clip)), 0, spec)
    assertBlurred(blurred, plain, DoubleArray(plain.width * plain.height) { 1.0 }, sigma, minSpread)
  }

  // Every pixel [alpha] covers sits within the way through RGB's reach of the shared blur of the plain frame's luma,
  // and the reference moves the frame far enough that a sharp read would fail.
  private fun assertBlurred(
    blurred: Yuv420Frame,
    plain: Yuv420Frame,
    alpha: DoubleArray,
    sigma: Float = SIGMA,
    minSpread: Double = SHARP_SPREAD,
  ) {
    val width = plain.width
    val luma = DoubleArray(width * plain.height) { plain.luma(it % width, it / width).toDouble() }
    val expected = blurPlane(luma, alpha, width, plain.height, sigma.toDouble())
    var worst = 0.0
    var spread = 0.0
    for (index in expected.indices) {
      if (alpha[index] == 0.0) continue
      worst = maxOf(worst, abs(blurred.luma(index % width, index / width) - expected[index]))
      spread = maxOf(spread, abs(expected[index] - luma[index]))
    }
    withClue("the reference blur moves the frame by $spread codes, so the read is not of a sharp frame") {
      spread shouldBeGreaterThan minSpread
    }
    withClue("the drawn blur is $worst codes off the shared plan's at its worst pixel") {
      worst shouldBeLessThanOrEqual BLUR_CODES
    }
  }

  // The picture of a contained frame blurs by the shared plan up to its edge, with the bars counting for nothing, and
  // every bar sample, the rows beside the picture included, reads the codes the fill lands on with no blur at all.
  private fun assertContained(
    blurred: Yuv420Frame,
    plain: Yuv420Frame,
  ) {
    plain.width shouldBe CONTAINED_WIDTH
    val pictureRows = BAR_ROWS until plain.height - BAR_ROWS
    val alpha = DoubleArray(plain.width * plain.height) { if (it / plain.width in pictureRows) 1.0 else 0.0 }
    assertBlurred(blurred, plain, alpha)

    for (y in (0 until plain.height).filterNot { it in pictureRows }) {
      for (x in 0 until plain.width) {
        withClue("luma at $x, $y in the bar") { blurred.luma(x, y) shouldBe plain.luma(x, y) }
      }
    }
    for (row in (0 until plain.height / 2).filterNot { 2 * it in pictureRows }) {
      for (column in 0 until plain.width / 2) {
        withClue("chroma at $column, $row in the bar") {
          blurred.cb(column, row) shouldBe plain.cb(column, row)
          blurred.cr(column, row) shouldBe plain.cr(column, row)
        }
      }
    }
  }

  private fun assertVignetted(
    drawn: Yuv420Frame,
    plain: Yuv420Frame,
    vignette: Vignette,
  ) {
    for ((x, y) in VIGNETTE_POINTS) {
      val gain = vignetteGainAt(x, y, vignette)
      val expected = LUMA_BLACK + (plain.luma(x, y) - LUMA_BLACK) * gain
      withClue("at $x, $y the vignette read ${drawn.luma(x, y)}, and the shared gain $gain puts it at $expected") {
        abs(drawn.luma(x, y) - expected) shouldBeLessThanOrEqual VIGNETTE_CODES
      }
    }
  }

  private fun vignetteGainAt(
    x: Int,
    y: Int,
    vignette: Vignette,
  ): Double =
    vignetteGain(
      (x + 0.5) / WIDTH,
      (y + 0.5) / HEIGHT,
      WIDTH.toDouble() / HEIGHT,
      vignette.strength.toDouble(),
      vignette.radius.toDouble(),
      vignette.softness.toDouble(),
    )

  // A grey left half and a right half at [right], stored half as wide as they show, with pixels twice as wide as they
  // are tall.
  private fun anamorphicClip(
    right: Int,
    vararg effects: EffectSpec,
  ): Clip =
    draw.lumaClipOf(
      WIDTH / 2,
      HEIGHT,
      "if(lt(X,${WIDTH / 4}),$GREY_LUMA,$right)",
      sampleAspect = 2,
      effects = effects.toList(),
    )

  private fun contain(): Crop = Crop(CONTAINED, fit = Fit.Contain)

  private fun containedClip(vararg after: EffectSpec): Clip =
    draw.clipOf(WIDTH, HEIGHT, ::edgeAt, listOf(contain()) + after)

  private fun edgeAt(x: Double): DoubleArray = DoubleArray(3) { if (x < 0.5) LIGHT else DARK }

  private companion object {
    // At a sigma of 0.05 the plan boxes this down by 3 and the half size by 2, and both land on whole pixels.
    const val WIDTH = 384
    const val HEIGHT = 216

    // At a sigma of 0.05 the plan boxes this down by 3, which leaves part of a box over.
    const val UNEVEN_WIDTH = 394
    const val SIGMA = 0.05f
    const val SMALL_SIGMA = 0.025f
    const val CLIP_SECONDS = 2
    const val READ_SLOT = 12
    const val LIGHT = 0.875
    const val DARK = 0.25
    const val GREY = 0.5

    // Every grey luma whose red, green and blue codes sit at 129 and above, once every 109 pixels.
    const val LIGHT_LUMA_LOW = 127
    const val LIGHT_LUMA_HIGH = 235
    const val NEUTRAL = 128
    const val LUMA_BLACK = 16.0
    const val GREY_LUMA = 126
    const val DARK_LUMA = 16
    const val HOISTED = "vhoist"

    // The way through RGB, ffmpeg's limit, against the plan's exact kernel and box.
    // Measured at 2.28 on 8.0.1, the edge beside a bar and a width the plan does not divide included.
    const val BLUR_CODES = 2.5

    // The way through RGB and the sixteen bit gain round each way, ffmpeg's limit. Measured at 1.25 on 8.0.1.
    const val VIGNETTE_CODES = 1.5

    // A blur of an edge 137 codes tall at a sigma of 0.05 of the width moves the pixels beside it by far more.
    const val SHARP_SPREAD = 40.0
    const val SMALL_SPREAD = 20.0

    // The centre, a point part way down the ramp, one near the radius on the diagonal and one near a corner.
    val VIGNETTE_POINTS = listOf(192 to 108, 58 to 108, 77 to 43, 12 to 11)

    // An 8 by 9 canvas over the clip is 192 by 216, and its picture fills the width at 108 rows tall, so a bar of 54
    // rows runs above and below it and a chroma row never straddles the edge.
    val CONTAINED = AspectRatio(8, 9)
    const val CONTAINED_WIDTH = 192
    const val BAR_ROWS = 54
    const val SLATE = 0xFF2F4F6F.toInt()
  }
}
