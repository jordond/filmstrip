package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.BrowserHdrTest.Companion.LUMA_TOLERANCE
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.browserRenderOf
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.render.compositorLightOf
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Overlays drawn on a kept grade, exported or previewed, and read back.
 *
 * Every overlay sits over the gap in front of a late primary, so what is under it is the fill's own light and never a
 * picture. Each case draws every cell of [HdrOverlayCells.CELLS], one frame per fill color. An export is read as
 * ten-bit codes and each cell has to land on [HdrOverlayCells.Cell.codes]. Every channel, chroma included, is held to
 * the luma tolerance [BrowserHdrTest] holds a fill to, so a colored texel that blends anywhere but linear display
 * light misses.
 *
 * A preview is read as eight-bit codes, and each cell has to land on [HdrOverlayCells.Cell.nits] shown through the SDR
 * display curve, clamped at reference white. A guard shows the signal blend and the sRGB blend of every
 * [HdrOverlayCells.BLENDS] cell the same way and checks twice the preview bound sits below the nearest of them, so a
 * preview that blends the wrong way cannot pass.
 */
class BrowserOverlayHdrTest {
  @Test
  fun anImageOverlayLandsWhereItsColorDoesOnBothTransfers() =
    runTest {
      if (!Filmstrip { webCodecsBackend() }.encodesHdr()) return@runTest

      assertLandsOnTheRule(grids(::imageAt))
    }

  @Test
  fun aTextOverlayLandsWhereItsColorDoesOnBothTransfers() =
    runTest {
      if (!Filmstrip { webCodecsBackend() }.encodesHdr()) return@runTest

      assertLandsOnTheRule(grids(::textAt))
    }

  @Test
  fun theOverlayBoundIsAtMostTheWidestBlendBound() {
    withClue("overlays are held to $LUMA_TOLERANCE codes, past ${HdrOverlayCells.MAX_BLEND_TOLERANCE}") {
      (LUMA_TOLERANCE <= HdrOverlayCells.MAX_BLEND_TOLERANCE) shouldBe true
    }
  }

  // The preview composites through the export's own compositor and shows its float buffer through the SDR display
  // curve, clamped at reference white. So the light the rule puts under each overlay, which the export lands on, is
  // what the preview draws once it is shown the same way.
  @Test
  fun aPreviewFrameShowsTheExportsImageOverlaysOnBothTransfers() =
    runTest {
      if (!Filmstrip { webCodecsBackend() }.encodesHdr()) return@runTest

      assertPreviewsTheRule(grids(::imageAt))
    }

  @Test
  fun aPreviewFrameShowsTheExportsTextOverlaysOnBothTransfers() =
    runTest {
      if (!Filmstrip { webCodecsBackend() }.encodesHdr()) return@runTest

      assertPreviewsTheRule(grids(::textAt))
    }

  // A preview that blends the encoded signals, or the eight-bit sRGB codes before mapping them, shows each blend cell
  // off what the rule shows. Twice the preview bound has to sit below the smallest of those misses, or one reading
  // could land within the bound of both the rule and a wrong blend.
  @Test
  fun thePreviewBoundIsBelowTheSmallestMissOfAWrongBlend() {
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        HdrOverlayCells.BLENDS.flatMap { cell ->
          val shown = presented(cell.nits())
          listOf(
            "$transfer signal blend of ${nameOf(cell)}" to presented(cell.signalBlendNits(transfer)),
            "sRGB blend of ${nameOf(cell)}" to presented(cell.srgbBlendNits()),
          ).map { (name, wrong) -> name to shown.missFrom(wrong) }
        }
      }
    val (name, smallest) = misses.minBy { it.second }

    withClue("a $name misses by only $smallest, too close for a bound of $PREVIEW_TOLERANCE to tell apart") {
      (2 * PREVIEW_TOLERANCE < smallest) shouldBe true
    }
  }

  private suspend fun assertPreviewsTheRule(grids: Map<Int, List<Placed>>) {
    HdrTransfer.entries.forEach { transfer ->
      grids.forEach { (background, overlays) -> assertPreviewed(transfer, background, overlays) }
    }
  }

  /**
   * Exports each fill's overlays over the gap on each transfer's grade and reads every one's centre against the rule,
   * so a failure names every overlay that missed on either transfer.
   */
  private suspend fun assertLandsOnTheRule(grids: Map<Int, List<Placed>>) {
    val misses =
      HdrTransfer.entries.flatMap { transfer ->
        grids.flatMap { (background, overlays) -> missesOf(transfer, background, overlays).map { "$transfer $it" } }
      }

    withClue(misses.joinToString("; ")) { misses.isEmpty() shouldBe true }
  }

  private suspend fun missesOf(
    transfer: HdrTransfer,
    background: Int,
    overlays: List<Placed>,
  ): List<String> {
    val composition = compositionOf(transfer, background, overlays.map { it.spec })
    val gap = gapFrameOf(transfer, composition)

    val rects = rectsOf(transfer, composition)
    return overlays.zip(rects).mapNotNull { (placed, rect) ->
      val (luma, cb, cr) = placed.cell.codes(transfer)
      val (readCb, readCr) = gap.chromaAt(rect.middleX, rect.middleY)
      val readLuma = gap.lumaAt(rect.middleX, rect.middleY)
      val landed =
        abs(readLuma - luma) <= LUMA_TOLERANCE &&
          abs(readCb - cb) <= LUMA_TOLERANCE &&
          abs(readCr - cr) <= LUMA_TOLERANCE
      if (landed) null else "${nameOf(placed.cell)} read ($readLuma, $readCb, $readCr) for ($luma, $cb, $cr)"
    }
  }

  /**
   * Previews [overlays] over a [background] fill on a [transfer] grade and reads every one's centre against what the
   * rule shows.
   */
  private suspend fun assertPreviewed(
    transfer: HdrTransfer,
    background: Int,
    overlays: List<Placed>,
  ) {
    val composition = compositionOf(transfer, background, overlays.map { it.spec })
    val resolved = engine().resolve(composition, SPEC).shouldBeInstanceOf<ResolveResult.Resolved>().composition
    val preview = resolved.toBrowserPreview(composition)

    try {
      val drawn = withClue("$transfer drew no preview frame in the gap") { preview.frameAt(GAP_TIME).shouldNotBeNull() }
      for ((placed, rect) in overlays.zip(rectsOf(transfer, composition))) {
        val column = (rect.middleX * drawn.size.width).toInt()
        val row = (rect.middleY * drawn.size.height).toInt()
        val offset = (row * drawn.size.width + column) * RGBA_CHANNELS
        val shown = presented(placed.cell.nits())
        repeat(3) { channel ->
          val previewed = drawn.pixels[offset + channel].toInt() and BYTE_MASK
          withClue(
            "$transfer ${nameOf(placed.cell)} channel $channel previewed $previewed, the rule shows ${shown[channel]}",
          ) {
            (abs(previewed - shown[channel]) <= PREVIEW_TOLERANCE) shouldBe true
          }
        }
      }
    } finally {
      preview.release()
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
    withClue("the $transfer file has no frame in the gap") {
      (gap.timestampUs < LATE_START.inWholeMicroseconds) shouldBe true
    }
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
    val resolved = engine().resolve(composition, SPEC).shouldBeInstanceOf<ResolveResult.Resolved>().composition
    val render = browserRenderOf(resolved, composition)
    render.hdrTransfer shouldBe transfer
    return render.compositionOverlays.map {
      withClue("an overlay is hidden in the gap") {
        it.drawAt(GAP_TIME).shouldNotBeNull()
      }.rect
    }
  }

  private suspend fun compositionOf(
    transfer: HdrTransfer,
    background: Int,
    overlays: List<EffectSpec>,
  ): EditComposition {
    val clip =
      makeHdrClip(transfer, width = WIDTH, height = HEIGHT, frames = CLIP_FRAMES, frameRate = FRAME_RATE) { PICTURE }
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clip))), start = LATE_START)),
      effects = overlays,
      audio = AudioSpec.Remove,
      fill = Fill.Solid(background),
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
    val success = (statuses.filterIsInstance<ExportStatus.Success>().singleOrNull()).shouldNotBeNull()
    return MediaSource.Uri(success.output.shouldBeInstanceOf<MediaSink.Uri>().uri)
  }

  private fun engine(): BrowserExportEngine =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
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

  private fun IntArray.missFrom(other: IntArray): Int = indices.maxOf { abs(this[it] - other[it]) }

  private fun nameOf(cell: HdrOverlayCells.Cell): String =
    "${cell.overlay.toUInt().toString(HEX)} at ${cell.opacity} over ${cell.background.toUInt().toString(HEX)}"

  /**
   * One overlay of a grid and the cell it draws.
   */
  private class Placed(
    val spec: EffectSpec,
    val cell: HdrOverlayCells.Cell,
  )

  /**
   * One grid per fill color of [HdrOverlayCells.CELLS], keyed by that color, with a column per overlay color and a
   * row per opacity, built by [at].
   */
  private fun grids(at: (color: Int, opacity: Float, column: Int, row: Int) -> EffectSpec): Map<Int, List<Placed>> =
    HdrOverlayCells.CELLS.groupBy { it.background }.mapValues { (_, cells) ->
      val colors = cells.map { it.overlay }.distinct()
      val opacities = cells.map { it.opacity }.distinct()
      withClue("a fill has more cells than a frame has room for") {
        (colors.size <= SLOTS && opacities.size <= SLOTS) shouldBe true
      }
      cells.map { cell ->
        val spec = at(cell.overlay, cell.opacity, colors.indexOf(cell.overlay), opacities.indexOf(cell.opacity))
        Placed(spec, cell)
      }
    }

  private fun imageAt(
    color: Int,
    opacity: Float,
    column: Int,
    row: Int,
  ): EffectSpec {
    val offset = OverlayFrame(offset = OverlayOffset(column * CELL_WIDTH, row * CELL_HEIGHT))
    return ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(PNG, color)),
      Corner.TopStart,
      margin = CELL_MARGIN,
      scale = IMAGE_SCALE,
      opacity = opacity,
      animation = OverlayAnimation { offset },
    )
  }

  // The plate is the text's own color, so the whole rect is that color and the centre reads it whether or not a glyph
  // crosses it.
  private fun textAt(
    color: Int,
    opacity: Float,
    column: Int,
    row: Int,
  ): EffectSpec {
    val frame = OverlayFrame(opacity = opacity)
    return TextOverlay(
      TEXT,
      TextStyle(fontSize = FONT_SIZE, color = color, backgroundColor = color),
      Anchor((column + HALF) / SLOTS, (row + HALF) / SLOTS),
      animation = OverlayAnimation { frame },
    )
  }

  private companion object {
    // The columns and the rows a frame lays its cells out on.
    const val SLOTS = 3

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

    // How far a preview reading may sit from what the rule shows, in eight-bit codes per channel. The preview runs no
    // codec and composites in RGBA16F buffers, so its reading is the rule's own. Measured at 0 on SwiftShader, the
    // renderer CI runs, and on Metal, checked by hand on a Mac.
    const val PREVIEW_TOLERANCE = 0

    const val HEX = 16
    const val MAX_CODE = 255f
    const val RGBA_CHANNELS = 4
    const val BYTE_MASK = 0xFF
  }
}
