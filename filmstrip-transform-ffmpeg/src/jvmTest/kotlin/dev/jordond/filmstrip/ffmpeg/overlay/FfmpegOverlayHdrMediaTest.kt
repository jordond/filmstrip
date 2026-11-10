package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.srgbToLinear
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_HDR_SPEC
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.ExportedOverlay
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.FIXTURE_SIZE
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.attributes
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.FULL_SCALE
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.centreOf
import dev.jordond.filmstrip.ffmpeg.support.channelsOf
import dev.jordond.filmstrip.ffmpeg.support.containedRect
import dev.jordond.filmstrip.ffmpeg.support.drawnProbe
import dev.jordond.filmstrip.ffmpeg.support.drawnRect
import dev.jordond.filmstrip.ffmpeg.support.frameTime
import dev.jordond.filmstrip.ffmpeg.support.opensOn
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.ffmpeg.support.pointIn
import dev.jordond.filmstrip.ffmpeg.support.tenBitCodesAt
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREY
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.HdrOverlayCells.BLACK
import dev.jordond.filmstrip.test.HdrOverlayCells.WHITE
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Image overlays on a kept HDR grade, read back as ten-bit codes against where the shared HDR light puts each color.
 *
 * A composition overlay is drawn over the top letterbox bar of the HDR fixture contained in a portrait frame, so the
 * background is a flat fill, or an opaque overlay drawn first, rather than a picture. Every expected code is
 * [HdrOverlayCells.Cell.codes] of the cell drawn. A blend that misses is reported next to where the cell's signal blend
 * and sRGB blend land, so the failure says which wrong blend ran.
 */
class FfmpegOverlayHdrMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `an opaque overlay on a kept PQ grade lands where the shared light puts its color`() =
    runTest(timeout = TIMEOUT) { assertOpaqueLandings(HdrTransfer.Pq) }

  @Test
  fun `an opaque overlay on a kept HLG grade lands where the shared light puts its color`() =
    runTest(timeout = TIMEOUT) { assertOpaqueLandings(HdrTransfer.Hlg) }

  @Test
  fun `partial alpha on a kept PQ grade blends in linear light`() =
    runTest(timeout = TIMEOUT) { assertLinearBlends(HdrTransfer.Pq) }

  @Test
  fun `partial alpha on a kept HLG grade blends in linear light`() =
    runTest(timeout = TIMEOUT) { assertLinearBlends(HdrTransfer.Hlg) }

  @Test
  fun `the blend bound is inside the widest one a blend cell still catches a wrong blend at`() {
    withClue("a blend bound of $CODE_TOLERANCE passes one") {
      (CODE_TOLERANCE <= HdrOverlayCells.MAX_BLEND_TOLERANCE).shouldBeTrue()
    }
  }

  // An overlay drawn from its raw sRGB codes, or without the turn from BT.709 to BT.2020 primaries, is the nearest
  // wrong answer for an opaque cell. On a colored cell both land far enough off that the fill's bound tells them
  // apart.
  @Test
  fun `a wrong opaque overlay lands outside twice the fill's bound on a colored cell`() {
    val colored = HdrOverlayCells.CELLS.filter { it.opacity == 1f && it.isColored() }
    withClue("the grid has no colored opaque cell") { colored.isNotEmpty().shouldBeTrue() }
    for (transfer in HdrTransfer.entries) {
      for (cell in colored) {
        val rule = cell.codes(transfer)
        val (red, green, blue) = channelsOf(cell.overlay).toList().map { it / FULL_SCALE }
        val raw = tenBitCodesFromSignal(floatArrayOf(red, green, blue))
        val unrotated = floatArrayOf(red, green, blue).map { srgbToLinear(it) * HDR_REFERENCE_WHITE_NITS }
        val wrongs =
          mapOf(
            "its raw sRGB codes" to raw,
            "BT.709 primaries" to tenBitCodesFromSignal(transfer.signalFromNits(unrotated.toFloatArray())),
          )
        wrongs.forEach { (name, wrong) ->
          val miss = rule.indices.maxOf { abs(wrong[it] - rule[it]) }
          withClue(
            "$transfer ${hex(cell.overlay)} drawn from $name lands $miss codes from ${rule.toList()}, " +
              "too close for a bound of $CODE_TOLERANCE to tell apart",
          ) { (2 * CODE_TOLERANCE < miss).shouldBeTrue() }
        }
      }
    }
  }

  @Test
  fun `a fading overlay on a kept PQ grade lands where the rule puts each frame's opacity`() =
    runTest(timeout = TIMEOUT) { assertFadeLandings(HdrTransfer.Pq) }

  @Test
  fun `a fading overlay on a kept HLG grade lands where the rule puts each frame's opacity`() =
    runTest(timeout = TIMEOUT) { assertFadeLandings(HdrTransfer.Hlg) }

  // The slide moves the overlay along the bar, and the branch it moves is the one carried onto the grade's signal, so
  // the expressions have to move the picture after the pre-map. MID_GREEN is read so a matrix left at BT.601 on the
  // way into the grade's YUV shows up in the chroma.
  @Test
  fun `an animated overlay on a kept grade is drawn where the sampler puts it`() =
    runTest(timeout = TIMEOUT) {
      val transfer = HdrTransfer.Pq
      val sliding = overlay(MID_GREEN, animation = slideIn(OverlayOffset(SLIDE_FRACTION, 0f), RAMP))
      val exported = exportedOverFill(transfer, MID_GREY, sliding)
      val size = exported.plan.output.size
      val span = TimeRange.of(Duration.ZERO, exported.plan.duration)
      val quarter = RAMP / 4
      val threeQuarters = RAMP * 3 / 4
      val early = drawnProbe(sliding, size, span, quarter)
      val late = drawnProbe(sliding, size, span, threeQuarters)
      val drawn = HdrOverlayCells.Cell(MID_GREEN, MID_GREY, 1f).codes(transfer)
      val fill = HdrOverlayCells.Cell(MID_GREY, MID_GREY, 1f).codes(transfer)
      withClue(
        "the slide's ${drawn.toList()} sits too close to the bar's ${fill.toList()} to tell one from the other",
      ) {
        drawn.indices.any { abs(drawn[it] - fill[it]) > 2 * CODE_TOLERANCE }.shouldBeTrue()
      }

      assertCodes(tenBitCodesAt(exported.file, early, quarter), drawn, "the slide at $quarter")
      assertCodes(
        tenBitCodesAt(exported.file, late, threeQuarters),
        drawn,
        "the slide at $threeQuarters",
      )
      assertCodes(tenBitCodesAt(exported.file, late, quarter), fill, "the bar at $late before the slide reaches it")

      exported.file.delete()
    }

  // The size moves on the branch carried onto the grade's signal, so no converter may sit after its scale. Read on the
  // frame in the middle of the ramp, at the centre of the drawn rectangle and in the band the picture only reaches by
  // growing, both inside the bar.
  @Test
  fun `a growing overlay on a kept PQ grade is drawn at the size the sampler answers`() =
    runTest(timeout = TIMEOUT) {
      val transfer = HdrTransfer.Pq
      val growing = overlay(MID_GREEN, animation = GROW)
      val exported = exportedOverFill(transfer, MID_GREY, growing)
      val size = exported.plan.output.size
      val span = TimeRange.of(Duration.ZERO, exported.plan.duration)
      val rate = exported.plan.output.frameRate
      val index = (RAMP * rate).inWholeSeconds.toInt() / 2
      val middle = frameTime(index, rate)
      val opened = drawnRect(growing, size, span, Duration.ZERO)
      val grown = drawnRect(growing, size, span, middle)
      val content = containedRect(FIXTURE_SIZE, size)
      withClue("the grown overlay at $grown is not inside the bar above $content") {
        (grown.bottom < content.top).shouldBeTrue()
      }
      val drawn = HdrOverlayCells.Cell(MID_GREEN, MID_GREY, 1f).codes(transfer)
      val probes =
        mapOf(
          "the centre" to centreOf(grown, size),
          "the grown band" to pointIn((opened.right + grown.right) / 2, (opened.bottom + grown.bottom) / 2, size),
        )

      probes.forEach { (where, probe) ->
        assertCodes(tenBitCodesAt(exported.file, probe, opensOn(index, exported.plan)), drawn, "$where on frame $index")
      }

      exported.file.delete()
    }

  @Test
  fun `the pre-map carries each texel onto the grade's signal and its alpha across untouched`() {
    val texels = intArrayOf(WHITE, MID_GREY, MID_GREEN, MID_GREEN and TRANSLUCENT_MASK or HALF_ALPHA)
    val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, 2, 2, texels, 0, 2) }
    val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    for (transfer in HdrTransfer.entries) {
      val resolution = resolve(ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart), transfer)
      val resolved =
        withClue("a translucent texel blends in linear light") {
          resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
        }
      val images =
        resolved.effect.fragment.auxInputs
          .map { it.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes }
      withClue("the picture is read once for its color and once for its alpha") { images.size shouldBe 2 }
      images[1] shouldBe images[0]
      val raster = ImageIO.read(ByteArrayInputStream(images[0])).raster

      texels.forEachIndexed { index, texel ->
        val signal = transfer.signalFromNits(authoredHdrNits(texel))
        val alpha = alphaOf(texel) * SIXTEEN_BIT_MAX / 255
        val expected = IntArray(3) { (signal[it] * SIXTEEN_BIT_MAX).roundToInt() } + alpha
        withClue("$transfer texel $index") { raster.getPixel(index % 2, index / 2, IntArray(4)) shouldBe expected }
      }
    }
  }

  @Test
  fun `an opaque still overlay on a kept grade resolves exactly`() {
    val png = pngOf(WHITE, OVERLAY_SIZE)
    val resolution = resolve(ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart), HdrTransfer.Pq)

    withClue("every texel it draws is opaque, so it lands on the rule") {
      resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
    }
  }

  @Test
  fun `an opaque overlay sliding on a kept grade resolves exactly`() {
    val sliding = overlay(WHITE, animation = slideIn(OverlayOffset(SLIDE_FRACTION, 0f), RAMP))

    for (transfer in HdrTransfer.entries) {
      withClue("$transfer: a slide keeps every texel opaque") {
        resolve(sliding, transfer).shouldBeInstanceOf<EffectResolution.Resolved>()
      }
    }
  }

  @Test
  fun `a fading or translucent overlay on a kept grade resolves exactly`() {
    for (transfer in HdrTransfer.entries) {
      for (spec in listOf(overlay(WHITE, animation = fade(inDuration = RAMP)), overlay(WHITE, HALF))) {
        withClue("$transfer: partial alpha blends in linear light") {
          resolve(spec, transfer).shouldBeInstanceOf<EffectResolution.Resolved>()
        }
      }
    }
  }

  // The blend runs on the part of the frame the overlay covers, cut on even pixels, rather than on the whole frame. The
  // cut holds the rectangle the shared placement draws and is at most one pixel wider on each side.
  @Test
  fun `the blend on a kept grade runs on the overlay's rectangle rather than the frame`() {
    val spec = overlay(WHITE)
    val resolved = resolve(spec, HdrTransfer.Pq).shouldBeInstanceOf<EffectResolution.Resolved>()
    val crop =
      withClue("a graded overlay names the region it blends on") {
        resolved.effect.fragment.crop
          .shouldNotBeNull()
      }
    val cut = "${crop.width}x${crop.height} at ${crop.x}, ${crop.y}"
    val (x, y) = listOf(crop.x, crop.y).map { it.shouldNotBeNull().toInt() }
    val (width, height) = listOf(crop.width, crop.height).map { it.toInt() }
    val rect = drawnRect(spec, FIXTURE_SIZE, TimeRange.of(Duration.ZERO, 2.seconds), Duration.ZERO)
    val left = rect.left * FIXTURE_SIZE.width
    val top = rect.top * FIXTURE_SIZE.height
    val right = rect.right * FIXTURE_SIZE.width
    val bottom = rect.bottom * FIXTURE_SIZE.height
    val what = "the cut $cut around $left, $top to $right, $bottom"

    withClue("$what is not exact") { crop.exact.shouldBeTrue() }
    withClue("$what is off the chroma grid") { (x % 2 == 0 && y % 2 == 0).shouldBeTrue() }
    withClue("$what misses it") {
      (x <= left + 1 && y <= top + 1 && x + width >= right - 1 && y + height >= bottom - 1).shouldBeTrue()
    }
    withClue("$what is loose") {
      (x >= left - 2 && y >= top - 2 && x + width <= right + 2 && y + height <= bottom + 2).shouldBeTrue()
    }
    withClue("$what is not smaller than the frame") {
      (width * height * 4 < FIXTURE_SIZE.width * FIXTURE_SIZE.height).shouldBeTrue()
    }
  }

  // A branch read with no timeline is one frame, so its decode runs once rather than on every frame of the region.
  @Test
  fun `a still overlay on a kept grade decodes its picture to light once`() {
    for (transfer in HdrTransfer.entries) {
      val still = resolve(overlay(WHITE, HALF), transfer).shouldBeInstanceOf<EffectResolution.Resolved>()
      val fragment = still.effect.fragment
      val (picture, mask) = fragment.auxInputs
      withClue("$transfer: the picture is decoded on its branch") {
        picture.chain.last() shouldBe fragment.chain.dropLast(PREMULTIPLY_NODES).last()
      }
      withClue("$transfer: a still picture is looped, so its decode runs per frame") { picture.timeline.shouldBeNull() }
      withClue("$transfer: an ungated still alpha is looped") { mask.timeline.shouldBeNull() }

      val windowed =
        ImageOverlay(
          image = ImageSource.ofBytes(pngOf(WHITE, OVERLAY_SIZE)),
          corner = Corner.TopStart,
          opacity = HALF,
          visibleDuring = TimeRange.of(Duration.ZERO, 1.seconds),
        )
      val gated =
        resolve(windowed, transfer)
          .shouldBeInstanceOf<EffectResolution.Resolved>()
          .effect.fragment.auxInputs
      withClue("$transfer: a gated overlay's picture still holds one frame") { gated[0].timeline.shouldBeNull() }
      withClue("$transfer: the gate reads the clock, so the alpha runs every frame") {
        gated[1].timeline.shouldNotBeNull()
      }

      val sliding = overlay(WHITE, animation = slideIn(OverlayOffset(SLIDE_FRACTION, 0f), RAMP))
      val driven =
        resolve(sliding, transfer)
          .shouldBeInstanceOf<EffectResolution.Resolved>()
          .effect.fragment.auxInputs
      withClue("$transfer: the slide moves both windows, so both branches run every frame") {
        driven.all { it.timeline != null }.shouldBeTrue()
      }

      val fading =
        resolve(
          overlay(WHITE, animation = fade(inDuration = RAMP)),
          transfer,
        ).shouldBeInstanceOf<EffectResolution.Resolved>()
      val (held, faded) = fading.effect.fragment.auxInputs
      withClue("$transfer: a fade leaves the picture where it is, so it holds one frame") {
        held.timeline.shouldBeNull()
      }
      // HLG's decode to light is a geq of its own that keeps the alpha it reads, so the fade is the geq that writes
      // another.
      val fades = { chain: List<FilterNode> ->
        chain.any { node ->
          node.name == "geq" &&
            node.arguments.any { it.key == "a" && it.value != "alpha(X,Y)" }
        }
      }
      withClue("$transfer: a faded picture fades its color") { fades(held.chain).shouldBeFalse() }
      withClue("$transfer: the fade drives the alpha on every frame") { faded.timeline.shouldNotBeNull() }
      withClue("$transfer: the fade is not on the alpha's branch") { fades(faded.chain).shouldBeTrue() }
    }
  }

  @Test
  fun `a text overlay is blended in linear light on a kept grade`() {
    val withText =
      RenderCapabilities(
        api = CAPABILITIES.api,
        supportsFragmentShader = false,
        supportsComputeShader = false,
        supportsHdr = true,
        colorSpaces = CAPABILITIES.colorSpaces,
        maxTextureSize = CAPABILITIES.maxTextureSize,
        features = setOf(RenderFeature.TextRendering),
      )
    val resolution =
      BuiltInEffectResolver().resolve(TextOverlay("caption"), withText, attributes(HdrTransfer.Pq, 2.seconds))

    resolution
      .shouldBeInstanceOf<EffectResolution.Resolved>()
      .effect.fragment.merge
      ?.name shouldBe "maskedmerge"
  }

  // The opaque cells of the shared grid, each drawn straight over its fill and held to the fill's bound.
  private suspend fun assertOpaqueLandings(transfer: HdrTransfer) {
    for (cell in HdrOverlayCells.CELLS.filter { it.opacity == 1f }) {
      val exported = exportedOverFill(transfer, cell.background, overlay(cell.overlay))
      val read = tenBitCodesAt(exported.file, probeOf(exported.plan))
      assertCodes(read, cell.codes(transfer), "$transfer ${hex(cell.overlay)} opaque")
      exported.file.delete()
    }
  }

  // The translucent cells of the shared grid. The fill is black and each cell's background is an opaque overlay of
  // its own, which lands on the shared light. Every cell is held to the fill's bound, which the guard above keeps
  // inside the widest one a blend cell still tells a wrong blend apart at.
  private suspend fun assertLinearBlends(transfer: HdrTransfer) {
    for (cell in HdrOverlayCells.CELLS.filter { it.opacity < 1f }) {
      val (color, background, opacity) = cell
      val exported = exportedOverFill(transfer, BLACK, overlay(color, opacity), under = overlay(background))
      val read = tenBitCodesAt(exported.file, probeOf(exported.plan))
      val wrong =
        if (cell.isBlend) {
          ", a signal blend lands at ${cell.signalBlendCodes(transfer).toList()} and an sRGB blend at " +
            "${cell.srgbBlendCodes(transfer).toList()}"
        } else {
          ""
        }

      assertCodes(read, cell.codes(transfer), "$transfer ${hex(color)} over ${hex(background)} at $opacity", wrong)
      exported.file.delete()
    }
  }

  // The fade is read on the output frames a quarter and three quarters of the way up, where the opacity the shared
  // sampler answers is neither end of the ramp. Each read opens just ahead of its frame, so it lands on that frame.
  private suspend fun assertFadeLandings(transfer: HdrTransfer) {
    val fading = overlay(WHITE, animation = fade(inDuration = RAMP))
    val exported = exportedOverFill(transfer, BLACK, fading, under = overlay(MID_GREY))
    val span = TimeRange.of(Duration.ZERO, exported.plan.duration)

    val rate = exported.plan.output.frameRate
    val frames = (RAMP * rate).inWholeSeconds.toInt()

    for (index in listOf(frames / 4, frames * 3 / 4)) {
      val opacity = fading.frameAt(frameTime(index, rate), span).shouldNotBeNull().opacity
      val read = tenBitCodesAt(exported.file, probeOf(exported.plan), opensOn(index, exported.plan))
      val expected = HdrOverlayCells.Cell(WHITE, MID_GREY, opacity).codes(transfer)
      assertCodes(read, expected, "$transfer fade on frame $index, opacity $opacity")
    }

    exported.file.delete()
  }

  private fun assertCodes(
    read: IntArray,
    expected: IntArray,
    what: String,
    wrong: String = "",
  ) {
    withClue("$what read ${read.toList()}, expected ${expected.toList()}$wrong") {
      read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE }.shouldBeTrue()
    }
  }

  private fun overlay(
    color: Int,
    opacity: Float = 1f,
    animation: OverlayAnimation? = null,
  ): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(pngOf(color, OVERLAY_SIZE)),
      corner = Corner.TopStart,
      opacity = opacity,
      animation = animation,
    )

  // The overlay's centre, which sits inside the top letterbox bar on the fixture's portrait frame.
  private fun probeOf(plan: ExportPlan): Pair<Int, Int> {
    val span = TimeRange.of(Duration.ZERO, plan.duration)
    val spec = overlay(WHITE)
    val rect = drawnRect(spec, plan.output.size, span, Duration.ZERO)
    val content = containedRect(FIXTURE_SIZE, plan.output.size)
    withClue("the overlay at $rect is not inside the bar above $content") { (rect.bottom < content.top).shouldBeTrue() }
    return centreOf(rect, plan.output.size)
  }

  private suspend fun exportedOverFill(
    transfer: HdrTransfer,
    fill: Int,
    overlay: ImageOverlay,
    under: ImageOverlay? = null,
  ): ExportedOverlay {
    val source = if (transfer == HdrTransfer.Pq) fixture.hdr else fixture.hdrHlg
    val output = File.createTempFile("filmstrip-hdr-overlay", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        track { clip(MediaSource.of(source.absolutePath)) }
        effects {
          crop(AspectRatio.Portrait, fit = Fit.Contain)
          under?.let { add(it) }
          add(overlay)
        }
        fill(Fill.Solid(fill))
      }
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, COLOR_READ_HDR_SPEC)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      }
    val statuses = fixture.filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList()
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    finished.shouldBeInstanceOf<ExportStatus.Success>()
    return ExportedOverlay(output, plan)
  }

  private fun resolve(
    spec: EffectSpec,
    transfer: HdrTransfer,
  ): EffectResolution? = BuiltInEffectResolver().resolve(spec, CAPABILITIES, attributes(transfer, 2.seconds))

  private fun alphaOf(argb: Int): Int = argb ushr 24

  private fun HdrOverlayCells.Cell.isColored(): Boolean =
    channelsOf(overlay).let { (red, green, blue) -> red != green || green != blue }

  private fun hex(color: Int): String = color.toUInt().toString(16)

  private companion object {
    // The geq that premultiplies the region's light by its alpha, which its chain ends on after the decode to light.
    const val PREMULTIPLY_NODES = 1

    const val HALF = 0.5f

    // A texel at half alpha, built from an opaque color by swapping its alpha byte.
    const val TRANSLUCENT_MASK = 0x00FFFFFF
    const val HALF_ALPHA = 0x80 shl 24

    // Far enough along the bar that the rectangle at a quarter of the slide and the one at three quarters do not
    // overlap, and short of the bar's far end.
    const val SLIDE_FRACTION = 0.3f
    val RAMP = 1.seconds

    // Doubles the overlay over the ramp, so the middle of it is drawn at one and a half times.
    val GROW = OverlayAnimation { time -> OverlayFrame(scale = 1f + (time.elapsed / RAMP).toFloat().coerceIn(0f, 1f)) }
  }
}
