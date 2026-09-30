package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
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
import dev.jordond.filmstrip.ffmpeg.ExportConstants.CODE_DRIFT
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.BLEND_DRIFT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREY
import dev.jordond.filmstrip.test.OverlayPixels.hdrSourceOver
import dev.jordond.filmstrip.transform.internal.tenBitCodesFromSignal
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
 * background is a flat fill, or an opaque overlay drawn first, rather than a picture. Every expected code comes from
 * [authoredHdrNits], [hdrSourceOver], [signalFromNits] and [tenBitCodesFromSignal].
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
      val drawn = expectedCodes(transfer, MID_GREY, MID_GREEN, 1f)

      assertCodes(tenBitCodesAt(exported.file, early, quarter), drawn, "the slide at $quarter")
      assertCodes(
        tenBitCodesAt(exported.file, late, threeQuarters),
        drawn,
        "the slide at $threeQuarters",
      )
      val bare = tenBitCodesAt(exported.file, late, quarter)
      assertTrue(
        bare.indices.any { abs(bare[it] - drawn[it]) > CODE_DRIFT },
        "the overlay has already reached $late at $quarter: $bare",
      )

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
        val expected = IntArray(3) { (signal[it] * WIDE_CODE_MAX).roundToInt() } + alphaOf(texel) * WIDE_CODE_MAX / 255
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

  // A branch read with no timeline is one frame, so the geq on it runs once rather than on every frame of the region.
  @Test
  fun `a still overlay on a kept grade decodes its picture to light once`() {
    for (transfer in HdrTransfer.entries) {
      val still = assertIs<EffectResolution.Resolved>(resolve(overlay(WHITE, HALF), transfer))
      val (picture, mask) = still.effect.fragment.auxInputs
      assertTrue(picture.chain.any { it.name == "geq" }, "$transfer: the picture is decoded to light on its branch")
      assertEquals(null, picture.timeline, "$transfer: a still picture is looped, so its geq runs per frame")
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
      val (held, faded) = fading.effect.fragment.auxInputs
      assertEquals(null, held.timeline, "$transfer: a fade leaves the picture where it is, so it holds one frame")
      assertTrue(held.chain.none { it.name == "sendcmd" }, "$transfer: nothing drives a faded picture")
      assertNotNull(faded.timeline, "$transfer: the fade drives the alpha on every frame")
      assertEquals(1, fading.effect.fragment.sidecars.size, "$transfer: only the alpha's commands are written")
    }
  }

  @Test
  fun `a text overlay stays refused on a kept grade`() {
    assertIs<EffectResolution.Unsupported>(resolve(TextOverlay("caption"), HdrTransfer.Pq))
  }

  private suspend fun assertOpaqueLandings(transfer: HdrTransfer) {
    if (!fixture.encodesHdr()) return
    for (colour in listOf(WHITE, MID_GREY, MID_GREEN)) {
      val exported = exportedOverFill(transfer, MID_GREY, overlay(colour))
      val read = tenBitCodesAt(exported.file, probeOf(exported.plan))
      assertCodes(read, expectedCodes(transfer, MID_GREY, colour, 1f), "$transfer ${hex(colour)} opaque")
      exported.file.delete()
    }
  }

  // Each colour over a background it differs from, so the blend has two lights to mix. The fill is black and a
  // background other than black is an opaque overlay of its own, which lands on the shared light. A blend of the signal
  // lands between about 10 and 270 codes from the rule on these, summed over the three channels, so the reads are held
  // to BLEND_DRIFT rather than the looser CODE_DRIFT.
  private suspend fun assertLinearBlends(transfer: HdrTransfer) {
    if (!fixture.encodesHdr()) return
    for ((colour, background) in listOf(WHITE to MID_GREY, MID_GREY to BLACK, MID_GREEN to MID_GREY)) {
      for (opacity in listOf(HALF, QUARTER)) {
        val exported = exportedOverFill(transfer, BLACK, overlay(colour, opacity), under = overlay(background))
        val read = tenBitCodesAt(exported.file, probeOf(exported.plan))
        val overlaySignal = transfer.signalFromNits(authoredHdrNits(colour))
        val underSignal = transfer.signalFromNits(authoredHdrNits(background))
        val signalBlend =
          tenBitCodesFromSignal(FloatArray(3) { opacity * overlaySignal[it] + (1f - opacity) * underSignal[it] })
        val rule = expectedCodes(transfer, background, colour, opacity)
        val what = "$transfer ${hex(colour)} over ${hex(background)} at $opacity"

        assertCodes(read, rule, what, BLEND_DRIFT)
        assertTrue(
          distance(read, rule) < distance(read, signalBlend),
          "$what read ${read.toList()}, ${rule.toList()} from linear light and ${signalBlend.toList()} from the signal",
        )
        exported.file.delete()
      }
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
      val expected = expectedCodes(transfer, MID_GREY, WHITE, opacity)
      assertCodes(read, expected, "$transfer fade on frame $index, opacity $opacity", BLEND_DRIFT)
    }

    exported.file.delete()
  }

  private fun expectedCodes(
    transfer: HdrTransfer,
    background: Int,
    colour: Int,
    alpha: Float,
  ): IntArray {
    val landed = hdrSourceOver(authoredHdrNits(background), colour, alpha)
    return tenBitCodesFromSignal(transfer.signalFromNits(landed))
  }

  private fun assertCodes(
    read: IntArray,
    expected: IntArray,
    what: String,
    drift: Int = CODE_DRIFT,
  ) {
    assertTrue(
      read.indices.all { abs(read[it] - expected[it]) <= drift },
      "$what read ${read.toList()}, expected ${expected.toList()}",
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
  ): EffectResolution? =
    BuiltInEffectResolver().resolve(
      spec,
      RenderCapabilities(
        api = RenderApi.FilterGraph,
        supportsFragmentShader = false,
        supportsComputeShader = false,
        supportsHdr = true,
        colorSpaces = setOf(ColorSpace.Bt2020),
        maxTextureSize = MAX_TEXTURE,
        features = emptySet(),
      ),
      Attributes(
        inputSize = FIXTURE_SIZE,
        outputSize = FIXTURE_SIZE,
        layoutSize = FIXTURE_SIZE,
        colorSpace = ColorSpace.Bt2020,
        hdrTransfer = transfer,
        frameRate = RATE,
        span = TimeRange.of(Duration.ZERO, 2.seconds),
      ),
    )

  private fun distance(
    read: IntArray,
    expected: IntArray,
  ): Int = read.indices.sumOf { abs(read[it] - expected[it]) }

  private fun alphaOf(argb: Int): Int = argb ushr 24

  private fun hex(colour: Int): String = colour.toUInt().toString(16)

  private companion object {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val HALF = 0.5f
    const val QUARTER = 0.25f

    // A texel at half alpha, built from an opaque colour by swapping its alpha byte.
    const val TRANSLUCENT_MASK = 0x00FFFFFF
    const val HALF_ALPHA = 0x80 shl 24

    // What the pre-mapped PNG stores a signal of one as.
    const val WIDE_CODE_MAX = 0xFFFF

    // The HDR fixtures' frame, which the portrait crop contains.
    val FIXTURE_SIZE = Size(1280, 720)
    const val RATE = 30f
    const val MAX_TEXTURE = 16_384

    // Far enough along the bar that the rectangle at a quarter of the slide and the one at three quarters do not
    // overlap, and short of the bar's far end.
    const val SLIDE_FRACTION = 0.3f
    val RAMP = 1.seconds
  }
}
