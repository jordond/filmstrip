package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.HdrRender.FIXTURE_SIZE
import dev.jordond.filmstrip.ffmpeg.HdrRender.attributes
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.FULL_SCALE
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.srgbToLinear
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREY
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.HdrOverlayCells.BLACK
import dev.jordond.filmstrip.test.HdrOverlayCells.WHITE
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Image overlays on a kept HDR grade, read back as ten-bit codes against where the shared HDR light puts each colour.
 *
 * A composition overlay is drawn over the top letterbox bar of the HDR fixture contained in a portrait frame, so the
 * background is a flat fill, or an opaque overlay drawn first, rather than a picture. Every expected code is
 * [HdrOverlayCells.Cell.codes] of the cell drawn. A blend that misses is reported next to where the cell's signal blend
 * and sRGB blend land, so the failure says which wrong blend ran.
 */
class FfmpegOverlayHdrTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `an opaque overlay on a kept PQ grade lands where the shared light puts its colour`() =
    runTest(timeout = TIMEOUT) { assertOpaqueLandings(HdrTransfer.Pq) }

  @Test
  fun `an opaque overlay on a kept HLG grade lands where the shared light puts its colour`() =
    runTest(timeout = TIMEOUT) { assertOpaqueLandings(HdrTransfer.Hlg) }

  @Test
  fun `partial alpha on a kept PQ grade blends in linear light`() =
    runTest(timeout = TIMEOUT) { assertLinearBlends(HdrTransfer.Pq) }

  @Test
  fun `partial alpha on a kept HLG grade blends in linear light`() =
    runTest(timeout = TIMEOUT) { assertLinearBlends(HdrTransfer.Hlg) }

  @Test
  fun `the blend bound is inside the widest one a blend cell still catches a wrong blend at`() {
    assertTrue(CODE_TOLERANCE <= HdrOverlayCells.MAX_BLEND_TOLERANCE, "a blend bound of $CODE_TOLERANCE passes one")
  }

  // An overlay drawn from its raw sRGB codes, or without the turn from BT.709 to BT.2020 primaries, is the nearest
  // wrong answer for an opaque cell. On a coloured cell both land far enough off that the fill's bound tells them
  // apart.
  @Test
  fun `a wrong opaque overlay lands outside twice the fill's bound on a coloured cell`() {
    val coloured = HdrOverlayCells.CELLS.filter { it.opacity == 1f && it.isColoured() }
    assertTrue(coloured.isNotEmpty(), "the grid has no coloured opaque cell")
    for (transfer in HdrTransfer.entries) {
      for (cell in coloured) {
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
          assertTrue(
            2 * CODE_TOLERANCE < miss,
            "$transfer ${hex(cell.overlay)} drawn from $name lands $miss codes from ${rule.toList()}, " +
              "too close for a bound of $CODE_TOLERANCE to tell apart",
          )
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
  // the commands have to reach the picture after the pre-map. MID_GREEN is read so a matrix left at BT.601 on the
  // way into the grade's YUV shows up in the chroma.
  @Test
  fun `an animated overlay on a kept grade is drawn where the sampler puts it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest
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
      assertTrue(
        drawn.indices.any { abs(drawn[it] - fill[it]) > 2 * CODE_TOLERANCE },
        "the slide's ${drawn.toList()} sits too close to the bar's ${fill.toList()} to tell one from the other",
      )

      assertCodes(tenBitCodesAt(exported.file, early, quarter), drawn, "the slide at $quarter")
      assertCodes(
        tenBitCodesAt(exported.file, late, threeQuarters),
        drawn,
        "the slide at $threeQuarters",
      )
      assertCodes(tenBitCodesAt(exported.file, late, quarter), fill, "the bar at $late before the slide reaches it")

      exported.file.delete()
    }

  @Test
  fun `the pre-map carries each texel onto the grade's signal and its alpha across untouched`() {
    val texels = intArrayOf(WHITE, MID_GREY, MID_GREEN, MID_GREEN and TRANSLUCENT_MASK or HALF_ALPHA)
    val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, 2, 2, texels, 0, 2) }
    val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    for (transfer in HdrTransfer.entries) {
      val resolution = resolve(ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart), transfer)
      val resolved = assertIs<EffectResolution.Resolved>(resolution, "a translucent texel blends in linear light")
      val images =
        resolved.effect.fragment.auxInputs
          .map { assertIs<ImageSource.Bytes>(it.image).bytes }
      assertEquals(2, images.size, "the picture is read once for its colour and once for its alpha")
      assertContentEquals(images[0], images[1])
      val raster = ImageIO.read(ByteArrayInputStream(images[0])).raster

      texels.forEachIndexed { index, texel ->
        val signal = transfer.signalFromNits(authoredHdrNits(texel))
        val alpha = alphaOf(texel) * SIXTEEN_BIT_MAX / 255
        val expected = IntArray(3) { (signal[it] * SIXTEEN_BIT_MAX).roundToInt() } + alpha
        assertContentEquals(expected, raster.getPixel(index % 2, index / 2, IntArray(4)), "$transfer texel $index")
      }
    }
  }

  @Test
  fun `an opaque still overlay on a kept grade resolves exactly`() {
    val png = pngOf(WHITE, OVERLAY_SIZE)
    val resolution = resolve(ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart), HdrTransfer.Pq)

    assertIs<EffectResolution.Resolved>(resolution, "every texel it draws is opaque, so it lands on the rule")
  }

  @Test
  fun `an opaque overlay sliding on a kept grade resolves exactly`() {
    val sliding = overlay(WHITE, animation = slideIn(OverlayOffset(SLIDE_FRACTION, 0f), RAMP))

    for (transfer in HdrTransfer.entries) {
      assertIs<EffectResolution.Resolved>(resolve(sliding, transfer), "$transfer: a slide keeps every texel opaque")
    }
  }

  @Test
  fun `a fading or translucent overlay on a kept grade resolves exactly`() {
    for (transfer in HdrTransfer.entries) {
      for (spec in listOf(overlay(WHITE, animation = fadeIn(RAMP)), overlay(WHITE, HALF))) {
        assertIs<EffectResolution.Resolved>(resolve(spec, transfer), "$transfer: partial alpha blends in linear light")
      }
    }
  }

  // The blend runs on the part of the frame the overlay covers, cut on even pixels, rather than on the whole frame. The
  // cut holds the rectangle the shared placement draws and is at most one pixel wider on each side.
  @Test
  fun `the blend on a kept grade runs on the overlay's rectangle rather than the frame`() {
    val spec = overlay(WHITE)
    val resolved = assertIs<EffectResolution.Resolved>(resolve(spec, HdrTransfer.Pq))
    val crop = assertNotNull(resolved.effect.fragment.crop, "a graded overlay names the region it blends on")
    val cut = crop.arguments.associate { it.key to it.value }
    val (x, y, width, height) = listOf("x", "y", "w", "h").map { assertNotNull(cut[it]).toInt() }
    val rect = drawnRect(spec, FIXTURE_SIZE, TimeRange.of(Duration.ZERO, 2.seconds), Duration.ZERO)
    val left = rect.left * FIXTURE_SIZE.width
    val top = rect.top * FIXTURE_SIZE.height
    val right = rect.right * FIXTURE_SIZE.width
    val bottom = rect.bottom * FIXTURE_SIZE.height
    val what = "the cut $cut around $left, $top to $right, $bottom"

    assertEquals("1", cut["exact"])
    assertTrue(x % 2 == 0 && y % 2 == 0, "$what is off the chroma grid")
    assertTrue(x <= left + 1 && y <= top + 1 && x + width >= right - 1 && y + height >= bottom - 1, "$what misses it")
    assertTrue(x >= left - 2 && y >= top - 2 && x + width <= right + 2 && y + height <= bottom + 2, "$what is loose")
    assertTrue(width * height * 4 < FIXTURE_SIZE.width * FIXTURE_SIZE.height, "$what is not smaller than the frame")
  }

  // A branch read with no timeline is one frame, so its decode runs once rather than on every frame of the region.
  @Test
  fun `a still overlay on a kept grade decodes its picture to light once`() {
    for (transfer in HdrTransfer.entries) {
      val still = assertIs<EffectResolution.Resolved>(resolve(overlay(WHITE, HALF), transfer))
      val fragment = still.effect.fragment
      val (picture, mask) = fragment.auxInputs
      assertEquals(fragment.chain.last(), picture.chain.last(), "$transfer: the picture is decoded on its branch")
      assertEquals(null, picture.timeline, "$transfer: a still picture is looped, so its decode runs per frame")
      assertEquals(null, mask.timeline, "$transfer: an ungated still alpha is looped")

      val windowed =
        ImageOverlay(
          image = ImageSource.ofBytes(pngOf(WHITE, OVERLAY_SIZE)),
          corner = Corner.TopStart,
          opacity = HALF,
          visibleDuring = TimeRange.of(Duration.ZERO, 1.seconds),
        )
      val gated = assertIs<EffectResolution.Resolved>(resolve(windowed, transfer)).effect.fragment.auxInputs
      assertEquals(null, gated[0].timeline, "$transfer: a gated overlay's picture still holds one frame")
      assertNotNull(gated[1].timeline, "$transfer: the gate reads the clock, so the alpha runs every frame")

      val sliding = overlay(WHITE, animation = slideIn(OverlayOffset(SLIDE_FRACTION, 0f), RAMP))
      val driven = assertIs<EffectResolution.Resolved>(resolve(sliding, transfer)).effect.fragment.auxInputs
      assertTrue(driven.all { it.timeline != null }, "$transfer: commands move both branches, so both run every frame")

      val fading = assertIs<EffectResolution.Resolved>(resolve(overlay(WHITE, animation = fadeIn(RAMP)), transfer))
      val branches = fading.effect.fragment.auxInputs
      val (held, faded) = branches
      assertEquals(null, held.timeline, "$transfer: a fade leaves the picture where it is, so it holds one frame")
      assertTrue(held.chain.none { it.name == "sendcmd" }, "$transfer: nothing drives a faded picture")
      assertNotNull(faded.timeline, "$transfer: the fade drives the alpha on every frame")
      val commands = branches.flatMap { it.chain }.filter { it.name == "sendcmd" }
      assertEquals(1, commands.size, "$transfer: only the alpha's commands are written")
    }
  }

  @Test
  fun `a text overlay stays refused on a kept grade`() {
    assertIs<EffectResolution.Unsupported>(resolve(TextOverlay("caption"), HdrTransfer.Pq))
  }

  // The opaque cells of the shared grid, each drawn straight over its fill and held to the fill's bound.
  private suspend fun assertOpaqueLandings(transfer: HdrTransfer) {
    if (!fixture.encodesHdr()) return
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
    if (!fixture.encodesHdr()) return
    for (cell in HdrOverlayCells.CELLS.filter { it.opacity < 1f }) {
      val (colour, background, opacity) = cell
      val exported = exportedOverFill(transfer, BLACK, overlay(colour, opacity), under = overlay(background))
      val read = tenBitCodesAt(exported.file, probeOf(exported.plan))
      val wrong =
        if (cell.isBlend) {
          ", a signal blend lands at ${cell.signalBlendCodes(transfer).toList()} and an sRGB blend at " +
            "${cell.srgbBlendCodes(transfer).toList()}"
        } else {
          ""
        }

      assertCodes(read, cell.codes(transfer), "$transfer ${hex(colour)} over ${hex(background)} at $opacity", wrong)
      exported.file.delete()
    }
  }

  // The fade is read on the output frames a quarter and three quarters of the way up, where the opacity the shared
  // sampler answers is neither end of the ramp. Each read opens just ahead of its frame, so it lands on that frame.
  private suspend fun assertFadeLandings(transfer: HdrTransfer) {
    if (!fixture.encodesHdr()) return
    val fading = overlay(WHITE, animation = fadeIn(RAMP))
    val exported = exportedOverFill(transfer, BLACK, fading, under = overlay(MID_GREY))
    val span = TimeRange.of(Duration.ZERO, exported.plan.duration)

    val rate = assertNotNull(exported.plan.output.frameRate)
    val frames = (RAMP * rate).inWholeSeconds.toInt()

    for (index in listOf(frames / 4, frames * 3 / 4)) {
      val opacity = assertNotNull(fading.frameAt(frameTime(index, rate), span)).opacity
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
    assertTrue(
      read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE },
      "$what read ${read.toList()}, expected ${expected.toList()}$wrong",
    )
  }

  private fun overlay(
    colour: Int,
    opacity: Float = 1f,
    animation: OverlayAnimation? = null,
  ): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(pngOf(colour, OVERLAY_SIZE)),
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
    assertTrue(rect.bottom < content.top, "the overlay at $rect is not inside the bar above $content")
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
      when (val verdict = fixture.filmstrip.plan(composition, ExportSpec(hdr = HdrMode.KeepHdr))) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      }
    val statuses = fixture.filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList()
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    assertIs<ExportStatus.Success>(finished)
    return ExportedOverlay(output, plan)
  }

  private fun resolve(
    spec: EffectSpec,
    transfer: HdrTransfer,
  ): EffectResolution? = BuiltInEffectResolver().resolve(spec, CAPABILITIES, attributes(transfer, 2.seconds))

  private fun alphaOf(argb: Int): Int = argb ushr 24

  private fun HdrOverlayCells.Cell.isColoured(): Boolean =
    channelsOf(overlay).let { (red, green, blue) -> red != green || green != blue }

  private fun hex(colour: Int): String = colour.toUInt().toString(16)

  private companion object {
    const val HALF = 0.5f

    // A texel at half alpha, built from an opaque colour by swapping its alpha byte.
    const val TRANSLUCENT_MASK = 0x00FFFFFF
    const val HALF_ALPHA = 0x80 shl 24

    // Far enough along the bar that the rectangle at a quarter of the slide and the one at three quarters do not
    // overlap, and short of the bar's far end.
    const val SLIDE_FRACTION = 0.3f
    val RAMP = 1.seconds
  }
}
