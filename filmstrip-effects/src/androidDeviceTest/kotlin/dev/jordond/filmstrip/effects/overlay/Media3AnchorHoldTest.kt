package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class Media3AnchorHoldTest {
  @Test
  fun aPointInsideTheFrameReachesMedia3AsGiven() {
    val settings = AnimatedOverlaySettings().update(placementAt(Anchor(0.8f, 0.25f)), PICTURE, 1f)

    settings.backgroundFrameAnchor.first shouldBe (0.6f plusOrMinus EPSILON)
    settings.backgroundFrameAnchor.second shouldBe (0.5f plusOrMinus EPSILON)
  }

  @Test
  fun aPointPastAnEdgeIsHeldOnThatEdgeSinceMedia3TakesNoAnchorOffTheFrame() {
    val settings = AnimatedOverlaySettings().update(placementAt(Anchor(1.4f, -0.3f)), PICTURE, 1f)

    settings.backgroundFrameAnchor.first shouldBe 1f
    settings.backgroundFrameAnchor.second shouldBe 1f
  }

  private fun placementAt(point: Anchor): OverlayPlacement =
    ImageOverlay(ImageSource.ofBytes(ByteArray(0)), OverlayPosition.At(point))
      .placedOn(FRAME, PICTURE)

  private companion object {
    val FRAME = Size(1280, 720)
    val PICTURE = Size(200, 100)
    const val EPSILON = 1e-4f
  }
}
