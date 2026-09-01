package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlin.test.Test

/**
 * The overlay placement arithmetic, on the cases that tell a Y flip from its absence.
 *
 * A symmetric placement lands in the same spot under either Y direction and proves nothing, so
 * every case here is asymmetric. Two opposite corners, unequal margins on the two axes, and an
 * overlay whose own aspect differs from the frame's.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreImageOverlaysTest {
  @Test
  fun `puts a bottom-end watermark the authored margin from both edges`() {
    val frame = Size(1920, 1080)
    val raster = Size(100, 50)
    val placement = ImageOverlay(image = NO_IMAGE, corner = Corner.BottomEnd).placedOn(frame, raster)

    placement.size shouldBe Size(384, 192)
    placement.overlayAnchor.x shouldBe 1f
    placement.overlayAnchor.y shouldBe 1f

    placement.transformOnto(frame, raster).useContents {
      // Scaled from the raster's own pixels to the drawn size.
      a shouldBe (3.84 plusOrMinus TOLERANCE)
      d shouldBe (3.84 plusOrMinus TOLERANCE)
      // The margin is a fraction of the shorter side, so both insets are 0.04 * 1080.
      tx shouldBe (1_492.8 plusOrMinus TOLERANCE)
      ty shouldBe (43.2 plusOrMinus TOLERANCE)
    }
  }

  // The overlay's own anchor flips against the overlay's height, not the frame's. Getting that
  // wrong moves a top-start badge by its own height, which a bottom-end one does not show.
  @Test
  fun `puts a top-start watermark the same distance from the top`() {
    val frame = Size(1920, 1080)
    val raster = Size(100, 50)
    val placement = ImageOverlay(image = NO_IMAGE, corner = Corner.TopStart).placedOn(frame, raster)

    placement.transformOnto(frame, raster).useContents {
      tx shouldBe (43.2 plusOrMinus TOLERANCE)
      // Bottom edge at 844.8 plus the overlay's own 192 puts the top edge at 1036.8, which is
      // 43.2 down from the frame's top.
      ty shouldBe (844.8 plusOrMinus TOLERANCE)
      ty + placement.size.height shouldBe (1_036.8 plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun `keeps the two corners a mirror of each other on a portrait frame`() {
    val frame = Size(1080, 1920)
    val raster = Size(200, 200)
    val start = ImageOverlay(image = NO_IMAGE, corner = Corner.TopStart).placedOn(frame, raster)
    val end = ImageOverlay(image = NO_IMAGE, corner = Corner.BottomEnd).placedOn(frame, raster)

    val startTransform = start.transformOnto(frame, raster).useContents { tx to ty }
    val endTransform = end.transformOnto(frame, raster).useContents { tx to ty }

    startTransform.first shouldBe (frame.width - endTransform.first - end.size.width plusOrMinus TOLERANCE)
    startTransform.second shouldBe (frame.height - endTransform.second - end.size.height plusOrMinus TOLERANCE)
  }

  // TextOverlay carries no margin. The same point is taken in the block and in the frame, so a bottom
  // centre anchor puts the block's bottom edge on the frame's.
  @Test
  fun `sits a bottom-centre text block on the frame's bottom edge`() {
    val frame = Size(1280, 720)
    val raster = Size(400, 80)
    val placement = TextOverlay("caption").placedOn(raster)

    placement.transformOnto(frame, raster).useContents {
      a shouldBe (1.0 plusOrMinus TOLERANCE)
      tx shouldBe (440.0 plusOrMinus TOLERANCE)
      ty shouldBe (0.0 plusOrMinus TOLERANCE)
    }
  }

  private companion object {
    val NO_IMAGE = ImageSource.ofBytes(ByteArray(0))
    const val TOLERANCE = 0.001
  }
}
