package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.TestFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.test.Test

/**
 * A translucent text plate in a browser preview frame read, against the shared blend of its color over the same frame
 * read without it.
 *
 * The plate reads back as its color drawn source over the frame at its alpha, within the eight-bit tolerance of a
 * frame read, so the preview composites the raster the export draws the export's way. The frame read is eight bits, so
 * how precisely the raster stores the plate is pinned by the raster's own tests, not here.
 */
class WebTextPlateParityTest {
  @Test
  fun aFrameReadBlendsATranslucentPlateOverTheFrameTheSharedWay() =
    contractTest { scope ->
      val plated = readback(scope, webFixtureComposition(listOf(TextOverlay(WORD, PLATED, Anchor.BottomCenter))))
      val bare = readback(scope, webFixtureComposition())

      // The block's bottom edge sits on the frame's, so two rows up from it is inside the plate's padding, below the
      // text.
      val x = plated.size.width / 2
      val y = plated.size.height - PADDING_ROW
      val opacity = (PLATE ushr ALPHA_SHIFT) / FULL
      val under = bare.codesAt(x, y)
      val expected =
        under.zip(AuthoredColors.channelsOf(PLATE)) { frame, over ->
          OverlayPixels.sourceOver(frame, over, opacity)
        }
      val drawn = plated.codesAt(x, y)

      withClue("the plate read $drawn at ($x, $y), where its color over the bare frame's $under gives $expected") {
        drawn.indices.all { abs(drawn[it] - expected[it]) <= AuthoredColors.TOLERANCE }.shouldBeTrue()
      }
    }

  private suspend fun readback(
    scope: CoroutineScope,
    composition: EditComposition,
  ): TestFrame {
    val engine = BrowserPlayerEngine(scope, BrowserPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig())
    var frame: TestFrame? = null
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }
      val read = engine.readback.awaitFrame(AT)
      read.presentationTime shouldBe AT
      frame = read.asTestFrame()
    }
    return checkNotNull(frame)
  }

  private fun TestFrame.codesAt(
    x: Int,
    y: Int,
  ): List<Int> {
    val offset = (y * size.width + x) * CHANNELS
    return List(3) { pixels[offset + it].toInt() and BYTE_MASK }
  }

  private companion object {
    val AT = PROBE_POSITIONS.first()

    // Color apart from its alpha, so a blend that swaps the two reads off.
    const val PLATE = 0x40C8C8C8
    val PLATED = TextStyle(fontSize = 0.1f, backgroundColor = PLATE)
    const val WORD = "Plate"

    // The plate pads its text by about a third of its cap height, and the text's descent sits above that.
    const val PADDING_ROW = 2

    const val CHANNELS = 4
    const val BYTE_MASK = 0xFF
    const val ALPHA_SHIFT = 24
    const val FULL = 255f
  }
}
