package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayEffect
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Composition overlays drawn over the letterbox fill of an export that keeps its grade, read back out of the written
 * file.
 *
 * A square crop of the 16:9 HDR fixtures leaves a bar above and below the picture that only the fill paints, so the
 * light under every overlay is [authoredHdrNits] of the fill. The grid is [HdrOverlayCells.CELLS], one export per fill
 * and opacity, and each cell is held to [HdrOverlayCells.Cell.codes]. An opaque overlay lands where a fill of its
 * color does and a partly opaque one blends with the bar in linear light. A blend that misses is reported next to
 * where the cell's signal blend and sRGB blend land, so the failure says which wrong blend ran.
 *
 * A case fails when its fixture is missing, as in [AppleExportTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleOverlayHdrTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `an image overlay on a kept grade lands where the shared blend puts it`() =
    runTest(timeout = TIMEOUT) {
      val misses = mutableListOf<String>()

      HdrTransfer.entries.forEach { transfer ->
        GRIDS.forEach { cells ->
          val badges =
            cells.mapIndexed { index, cell ->
              val png = OverlayPixels.pngOf(RASTER, cell.overlay)
              ImageOverlay(ImageSource.ofBytes(png), CORNERS[index], opacity = cell.opacity)
            }
          exported(transfer, "image", cells.first().background, badges) { path, size ->
            val frame =
              withClue("could not decode a frame of the $transfer export") {
                hdrFrameOf(path).shouldNotBeNull()
              }
            badges.zip(cells).forEach { (badge, cell) ->
              val rect = badge.placedOn(size, RASTER).rectOn(size)
              val x = (rect.left + rect.right) / 2f
              val y = (rect.top + rect.bottom) / 2f
              misses += missed(frame, transfer, cell, x, y, "image")
            }
          }
        }
      }

      withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
    }

  // The plate and the glyphs share one color, so every pixel of the block is that color whatever the font lays out,
  // and a point one cap height in from the anchored corner is inside it.
  @Test
  fun `text on a kept grade lands where the shared blend puts it`() =
    runTest(timeout = TIMEOUT) {
      val misses = mutableListOf<String>()

      HdrTransfer.entries.forEach { transfer ->
        GRIDS.forEach { cells ->
          val opacity = cells.first().opacity
          val held = if (opacity < 1f) OverlayAnimation { OverlayFrame(opacity = opacity) } else null
          val captions =
            cells.mapIndexed { index, cell ->
              val style = TextStyle(fontSize = FONT_SIZE, color = cell.overlay, backgroundColor = cell.overlay)
              TextOverlay(CAPTION, style, ANCHORS[index], animation = held)
            }
          exported(transfer, "text", cells.first().background, captions) { path, size ->
            val frame =
              withClue("could not decode a frame of the $transfer export") {
                hdrFrameOf(path).shouldNotBeNull()
              }
            captions.zip(cells).forEach { (caption, cell) ->
              val anchor = caption.anchor
              val x = anchor.x + (1f - 2f * anchor.x) * FONT_SIZE * size.height / size.width
              val y = anchor.y + (1f - 2f * anchor.y) * FONT_SIZE
              misses += missed(frame, transfer, cell, x, y, "text")
            }
          }
        }
      }

      withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
    }

  // Read inside the written file at a quarter and three quarters of the ramp. Both ends of a ramp agree whether the
  // opacity is added or multiplied in, so only the middle says which one ran, and the expectation comes from the
  // shared sampler rather than from a number copied out of it.
  @Test
  fun `a fade on a kept grade blends by the shared sampler in the middle of its ramp`() =
    runTest(timeout = TIMEOUT) {
      val misses = mutableListOf<String>()
      val transfer = HdrTransfer.Pq
      val white = HdrOverlayCells.WHITE
      val badge = ImageOverlay(ImageSource.ofBytes(OverlayPixels.pngOf(RASTER, white)), Corner.TopStart)
      val faded = ImageOverlay(badge.image, badge.corner, animation = fadeIn(RAMP))

      exported(transfer, "fade", BAR, listOf(faded)) { path, size ->
        val rect = faded.placedOn(size, RASTER).rectOn(size)
        RAMP_POINTS.forEach { time ->
          val opacity =
            withClue("the badge was not drawn at $time") {
              faded.frameAt(time, SPAN).shouldNotBeNull()
            }.opacity
          val frame = withClue("could not decode the frame at $time") { hdrFrameOf(path, time).shouldNotBeNull() }
          val x = (rect.left + rect.right) / 2f
          val y = (rect.top + rect.bottom) / 2f
          misses += missed(frame, transfer, HdrOverlayCells.Cell(white, BAR, opacity), x, y, "fade at $time")
        }
      }

      withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
    }

  @Test
  fun `a blend is held to a bound a wrong blend falls outside`() {
    withClue(
      "a blend held to $BLEND_TOLERANCE codes would pass a wrong blend, which the cells only promise to miss by " +
        "more than ${HdrOverlayCells.MAX_BLEND_TOLERANCE}",
    ) {
      (BLEND_TOLERANCE <= HdrOverlayCells.MAX_BLEND_TOLERANCE).shouldBeTrue()
    }
  }

  /**
   * Where [cell] was read at ([x], [y]), when it is off the shared blend by more than its bound.
   *
   * Every cell is held to [BLEND_TOLERANCE], the fill's own code tolerance, whether it is a blend or not. A blend cell
   * that misses also names the codes of its two wrong blends.
   */
  private fun missed(
    frame: HdrFrameProbe,
    transfer: HdrTransfer,
    cell: HdrOverlayCells.Cell,
    x: Float,
    y: Float,
    what: String,
  ): List<String> {
    val light = cell.nits()
    val expected = cell.codes(transfer).toList()
    val read = frame.codesAt(x, y)
    if (expected.indices.all { abs(read[it] - expected[it]) <= BLEND_TOLERANCE }) return emptyList()

    val wrong =
      if (cell.isBlend) {
        ", a signal blend lands at ${cell.signalBlendCodes(transfer).toList()} and an sRGB blend at " +
          "${cell.srgbBlendCodes(transfer).toList()}"
      } else {
        ""
      }
    return listOf(
      "$transfer $what ${cell.overlay.toUInt().toString(HEX)} at ${cell.opacity} over " +
        "${cell.background.toUInt().toString(HEX)} carried codes $read and ${frame.nitsAt(x, y)} nits, where the " +
        "shared blend asks for $expected and ${light.toList()} nits$wrong",
    )
  }

  /**
   * Exports a clip of the [transfer] fixture cropped square over a fill of [background] with [overlays] on the
   * composition, and hands [read] the written file and its size.
   */
  private suspend fun exported(
    transfer: HdrTransfer,
    name: String,
    background: Int,
    overlays: List<OverlayEffect>,
    read: (String, Size) -> Unit,
  ) {
    val source = hdrFixture(transfer)
    val composition =
      compositionOf {
        clip(MediaSource.of(source)) { trim(Duration.ZERO, CLIP) }
        fill(Fill.Solid(background))
        effects {
          crop(AspectRatio.Square, Fit.Contain)
          overlays.forEach { add(it) }
        }
      }
    val spec = ExportSpec(heightPx = 720, hdr = HdrMode.KeepHdr)
    val plan =
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error("the $transfer $name export was refused: ${verdict.reasons.map { it.message }}")
      }

    val finished = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.Temporary).toList() }.last()
    if (finished is ExportStatus.Failure) error("export failed: ${finished.error.message}")
    val path =
      finished
        .shouldBeInstanceOf<ExportStatus.Success>()
        .output
        .shouldBeInstanceOf<MediaSink.Path>()
        .path

    try {
      val written =
        filmstrip
          .probe(MediaSource.of(path))
          .shouldBeInstanceOf<ProbeResult.Success>()
          .info.video
      written?.hdrTransfer shouldBe transfer
      read(path, written.shouldNotBeNull().displaySize)
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private companion object {
    val TIMEOUT = 5.minutes

    val CLIP = 1.seconds
    val SPAN = TimeRange.of(Duration.ZERO, CLIP)

    // Six and eighteen frames in at thirty frames a second, so both land on a frame rather than between two.
    val RAMP = 800.milliseconds
    val RAMP_POINTS = listOf(RAMP / 4, RAMP * 3 / 4)

    const val BAR = AuthoredColors.MID_GREY

    // A blend is read off the same encode as the fill, so it holds to the fill's own bound.
    const val BLEND_TOLERANCE = FILL_CODE_TOLERANCE

    // One export per fill and opacity, at most three cells, one to each corner and anchor.
    val GRIDS = HdrOverlayCells.CELLS.groupBy { it.background to it.opacity }.values

    // A badge at the default scale and margin sits inside the bar at each of these corners, one cell to each.
    val CORNERS = listOf(Corner.TopStart, Corner.TopEnd, Corner.BottomStart)
    val RASTER = Size(4, 2)

    // Small enough that a plated caption stands inside the bar and two fit side by side along the top.
    val ANCHORS = listOf(Anchor.TopStart, Anchor.TopEnd, Anchor.BottomStart)
    const val CAPTION = "filmstrip"
    const val FONT_SIZE = 0.06f

    const val HEX = 16
  }
}
