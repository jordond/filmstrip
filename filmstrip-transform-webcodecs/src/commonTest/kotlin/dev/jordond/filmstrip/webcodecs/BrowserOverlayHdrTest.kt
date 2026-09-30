package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.sdrSignalFromNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.AuthoredColours
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.tenBitCodesFromSignal
import dev.jordond.filmstrip.webcodecs.BrowserHdrTest.Companion.LUMA_TOLERANCE
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.browserRenderOf
import dev.jordond.filmstrip.webcodecs.internal.compositorLightOf
import dev.jordond.filmstrip.webcodecs.internal.toBrowserPreview
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * Overlays drawn on a kept grade, exported, and read back as ten-bit codes.
 *
 * Every overlay sits over the gap in front of a late primary, so what is under it is the fill's own light and never a
 * picture. Each case draws white, [AuthoredColours.MID_GREY] and [AuthoredColours.MID_GREEN] at full, half and quarter
 * opacity in one frame, and each one has to land where [OverlayPixels.hdrSourceOver] puts it, through the shared
 * transfer and the shared code conversion. Every channel, chroma included, is held to the luma tolerance
 * [BrowserHdrTest] holds a fill to, so a coloured texel that blends anywhere but linear display light misses.
 */
class BrowserOverlayHdrTest {
  @Test
  fun anImageOverlayLandsWhereItsColourDoesOnBothTransfers() =
    runTest {
      if (!Filmstrip { webCodecsBackend() }.encodesHdr()) return@runTest

      assertLandsOnTheRule(grid(::imageAt))
    }

  @Test
  fun aTextOverlayLandsWhereItsColourDoesOnBothTransfers() =
    runTest {
      if (!Filmstrip { webCodecsBackend() }.encodesHdr()) return@runTest

      assertLandsOnTheRule(grid(::textAt))
    }

  // The preview composites through the export's own compositor and shows its float buffer through the SDR display
  // curve, clamped at reference white. So the light the rule puts under each overlay, which the export lands on, is
  // what the preview draws once it is shown the same way.
  @Test
  fun aPreviewFrameShowsTheExportsOverlaysOnBothTransfers() =
    runTest {
      if (!Filmstrip { webCodecsBackend() }.encodesHdr()) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        val overlays = grid(::imageAt)
        val composition = compositionOf(transfer, overlays.map { it.spec })
        val background = authoredHdrNits(AuthoredColours.MID_GREY)
        val resolved = assertIs<ResolveResult.Resolved>(engine().resolve(composition, SPEC)).composition
        val preview = resolved.toBrowserPreview(composition)

        try {
          val drawn = assertNotNull(preview.frameAt(GAP_TIME), "$transfer drew no preview frame in the gap")
          for ((placed, rect) in overlays.zip(rectsOf(transfer, composition))) {
            val column = (rect.middleX * drawn.size.width).toInt()
            val row = (rect.middleY * drawn.size.height).toInt()
            val offset = (row * drawn.size.width + column) * RGBA_CHANNELS
            val shown = presented(OverlayPixels.hdrSourceOver(background, placed.colour, placed.opacity))
            repeat(3) { channel ->
              val previewed = drawn.pixels[offset + channel].toInt() and BYTE_MASK
              assertTrue(
                abs(previewed - shown[channel]) <= OverlayPixels.ENCODED_TOLERANCE,
                "$transfer channel $channel at $rect previewed $previewed, and the rule shows ${shown[channel]}",
              )
            }
          }
        } finally {
          preview.release()
        }
      }
    }

  /**
   * Exports [overlays] over the gap on each transfer's grade and reads every one's centre against the rule, so a
   * failure names every overlay that missed on either transfer.
   */
  private suspend fun assertLandsOnTheRule(overlays: List<Placed>) {
    val misses = HdrTransfer.entries.flatMap { transfer -> missesOf(transfer, overlays).map { "$transfer $it" } }

    assertTrue(misses.isEmpty(), misses.joinToString("; "))
  }

  private suspend fun missesOf(
    transfer: HdrTransfer,
    overlays: List<Placed>,
  ): List<String> {
    val composition = compositionOf(transfer, overlays.map { it.spec })
    val gap = gapFrameOf(transfer, composition)
    val background = authoredHdrNits(AuthoredColours.MID_GREY)

    val rects = rectsOf(transfer, composition)
    return overlays.zip(rects).mapNotNull { (placed, rect) ->
      val light = OverlayPixels.hdrSourceOver(background, placed.colour, placed.opacity)
      val (luma, cb, cr) = tenBitCodesFromSignal(transfer.signalFromNits(light))
      val (readCb, readCr) = gap.chromaAt(rect.middleX, rect.middleY)
      val readLuma = gap.lumaAt(rect.middleX, rect.middleY)
      val landed =
        abs(readLuma - luma) <= LUMA_TOLERANCE &&
          abs(readCb - cb) <= LUMA_TOLERANCE &&
          abs(readCr - cr) <= LUMA_TOLERANCE
      if (landed) {
        null
      } else {
        "${placed.colour.toUInt().toString(HEX)} at ${placed.opacity} read ($readLuma, $readCb, $readCr) for " +
          "($luma, $cb, $cr)"
      }
    }
  }

  /**
   * The gap frame of [composition] exported on a [transfer] grade.
   */
  private suspend fun gapFrameOf(
    transfer: HdrTransfer,
    composition: EditComposition,
  ): TenBitFrame {
    val frames = decodeTenBitFrames(exported(composition, transfer), transfer)
    val gapUs = GAP_TIME.inWholeMicroseconds.toDouble()
    val gap = frames.minBy { abs(it.timestampUs - gapUs) }
    assertTrue(gap.timestampUs < LATE_START.inWholeMicroseconds, "the $transfer file has no frame in the gap")
    return gap
  }

  /**
   * Where the compositor draws each of the composition's overlays in the gap, read off the same lowering an export
   * runs, in the order the composition lists them.
   */
  private suspend fun rectsOf(
    transfer: HdrTransfer,
    composition: EditComposition,
  ): List<NormalizedRect> {
    val resolved = assertIs<ResolveResult.Resolved>(engine().resolve(composition, SPEC)).composition
    val render = browserRenderOf(resolved, composition)
    assertEquals(transfer, render.hdrTransfer)
    return render.compositionOverlays.map { assertNotNull(it.drawAt(GAP_TIME), "an overlay is hidden in the gap").rect }
  }

  private suspend fun compositionOf(
    transfer: HdrTransfer,
    overlays: List<EffectSpec>,
  ): EditComposition {
    val clip =
      makeHdrClip(transfer, width = WIDTH, height = HEIGHT, frames = CLIP_FRAMES, frameRate = FRAME_RATE) { PICTURE }
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clip))), start = LATE_START)),
      effects = overlays,
      audio = AudioSpec.Remove,
      fill = Fill.Solid(AuthoredColours.MID_GREY),
    )
  }

  private suspend fun exported(
    composition: EditComposition,
    transfer: HdrTransfer,
  ): MediaSource {
    val engine = engine()
    val plan =
      when (val verdict = engine.plan(composition, SPEC)) {
        is Verdict.Capable -> {
          verdict.plan
        }
        is Verdict.Degraded -> {
          verdict.plan
        }
        is Verdict.Incapable -> {
          throw AssertionError("the $transfer plan was refused: ${verdict.reasons.map { it.message }}")
        }
      }
    val statuses = engine.export(plan, MediaSink.Uri("")).toList()
    statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()?.let {
      throw AssertionError("the $transfer export failed: ${it.error.message}")
    }
    val success = assertNotNull(statuses.filterIsInstance<ExportStatus.Success>().singleOrNull())
    return MediaSource.Uri(assertIs<MediaSink.Uri>(success.output).uri)
  }

  private fun engine(): BrowserExportEngine =
    browserExportEngine(
      components = ComponentRegistry.Builder().add(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    )

  /**
   * Eight-bit codes the preview shows for [nits] of display light: held where the compositor's buffer holds it, then
   * clamped at reference white and put through the SDR display curve.
   */
  private fun presented(nits: FloatArray): IntArray {
    val light = compositorLightOf(nits)
    return IntArray(3) {
      (sdrSignalFromNits(light[it] * HDR_REFERENCE_WHITE_NITS).coerceAtMost(1f) * MAX_CODE).roundToInt()
    }
  }

  /**
   * One overlay of the grid and what it was authored with.
   */
  private class Placed(
    val spec: EffectSpec,
    val colour: Int,
    val opacity: Float,
  )

  /**
   * Every colour at every opacity, a column a colour and a row an opacity, built by [at].
   */
  private fun grid(at: (colour: Int, opacity: Float, column: Int, row: Int) -> EffectSpec): List<Placed> =
    COLOURS.flatMapIndexed { column, colour ->
      OPACITIES.mapIndexed { row, opacity -> Placed(at(colour, opacity, column, row), colour, opacity) }
    }

  private fun imageAt(
    colour: Int,
    opacity: Float,
    column: Int,
    row: Int,
  ): EffectSpec {
    val offset = OverlayFrame(offset = OverlayOffset(column * CELL_WIDTH, row * CELL_HEIGHT))
    return ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(PNG, colour)),
      Corner.TopStart,
      margin = CELL_MARGIN,
      scale = IMAGE_SCALE,
      opacity = opacity,
      animation = OverlayAnimation { offset },
    )
  }

  // The plate is the text's own colour, so the whole rect is that colour and the centre reads it whether or not a glyph
  // crosses it.
  private fun textAt(
    colour: Int,
    opacity: Float,
    column: Int,
    row: Int,
  ): EffectSpec {
    val frame = OverlayFrame(opacity = opacity)
    return TextOverlay(
      TEXT,
      TextStyle(fontSize = FONT_SIZE, color = colour, backgroundColor = colour),
      Anchor((column + HALF) / COLOURS.size, (row + HALF) / OPACITIES.size),
      animation = OverlayAnimation { frame },
    )
  }

  private companion object {
    // Paper white, which an opaque overlay has to land on at reference white and not at the format's peak.
    const val WHITE = 0xFFFFFFFF.toInt()
    val COLOURS = listOf(WHITE, AuthoredColours.MID_GREY, AuthoredColours.MID_GREEN)
    val OPACITIES = listOf(1f, 0.5f, 0.25f)

    const val WIDTH = 256
    const val HEIGHT = 192
    const val FRAME_RATE = 30
    const val CLIP_FRAMES = 6
    val LATE_START = 200.milliseconds

    // The middle of the gap, on the output's own frame grid.
    val GAP_TIME: Duration = outputSlotTime(outputSlotOf(LATE_START / 2, FRAME_RATE), FRAME_RATE)

    // The clip after the gap. The overlays never sit over it, so its light only has to be a legal grade.
    val PICTURE = floatArrayOf(120f, 180f, 75f)

    // A square badge a fifth of the frame wide, stepped across a third of the frame at a time.
    val PNG = Size(8, 8)
    const val IMAGE_SCALE = 0.2f
    const val CELL_MARGIN = 0.02f
    const val CELL_WIDTH = 0.3f
    const val CELL_HEIGHT = 0.32f

    const val TEXT = "MM"
    const val FONT_SIZE = 0.1f
    const val HALF = 0.5f

    val SPEC = ExportSpec(audioCodec = AudioCodec.None, frameRate = FRAME_RATE, hdr = HdrMode.KeepHdr)

    const val HEX = 16
    const val MAX_CODE = 255f
    const val RGBA_CHANNELS = 4
    const val BYTE_MASK = 0xFF
  }
}
