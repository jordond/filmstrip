package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A translucent text plate in a preview frame read, drawn over the fill inside a gap, against the blend an SDR export
 * draws from the same raster.
 *
 * The plate reads back as its color drawn source over the fill at its alpha, within the eight-bit tolerance of a frame
 * read, so the preview composites the export's raster the export's way. The frame read is eight bits, so how precisely
 * the raster stores the plate is pinned by the raster's own tests, not here.
 */
class AppleTextPlateParityHardwareTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a frame read blends a translucent plate over the fill by the shared blend`() =
    contractTest { scope ->
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        engine.awaitComposition(composition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val read = engine.readback.awaitFrame(IN_GAP)
        read.presentationTime shouldBe IN_GAP
        val frame = read.asTestFrame()

        // The block's bottom edge sits on the frame's, so a few rows up from it is inside the plate's padding.
        val x = frame.size.width / 2
        val y = frame.size.height - PADDING_ROW
        val offset = (y * frame.size.width + x) * CHANNELS
        val drawn = List(3) { frame.pixels[offset + it].toInt() and BYTE_MASK }
        val opacity = (PLATE ushr ALPHA_SHIFT) / FULL
        val expected =
          AuthoredColors.channelsOf(AuthoredColors.MID_GREY).zip(AuthoredColors.channelsOf(PLATE)) { under, over ->
            OverlayPixels.sourceOver(under, over, opacity)
          }

        withClue("the plate read $drawn at ($x, $y), where its color over the fill gives $expected") {
          drawn.indices.all { abs(drawn[it] - expected[it]) <= AuthoredColors.TOLERANCE }.shouldBeTrue()
        }
      }
    }

  // The fixture clip waits GAP_LENGTH on its track, so the video opens on a gap only the fill covers.
  private fun composition(): Composition =
    Composition(
      tracks =
        listOf(
          Track(
            listOf(Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH))),
            start = GAP_LENGTH,
          ),
        ),
      effects = listOf(TextOverlay(WORD, TextStyle(fontSize = 0.1f, backgroundColor = PLATE), Anchor.BottomCenter)),
      fill = Fill.Solid(AuthoredColors.MID_GREY),
    )

  private companion object {
    val GAP_LENGTH = 1.seconds

    // Inside the gap and on the fixture's 30fps grid, rather than at either end of the gap.
    val IN_GAP = 400.milliseconds

    // Color apart from its alpha, so a blend that swaps the two reads off.
    const val PLATE = 0x40C8C8C8
    const val WORD = "Plate"

    // The plate pads its text by about a third of its cap height, eleven rows on the fixture's frame.
    const val PADDING_ROW = 4

    const val CHANNELS = 4
    const val BYTE_MASK = 0xFF
    const val ALPHA_SHIFT = 24
    const val FULL = 255f
  }
}
