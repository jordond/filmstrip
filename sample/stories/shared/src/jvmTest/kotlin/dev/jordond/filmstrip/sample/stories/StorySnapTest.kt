package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.geometry.Anchor
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StorySnapTest {
  @Test
  fun aCentreJustOffTheMiddleSnapsOntoItAndDrawsThatGuide() {
    val snap = snapped(OverlayTransform(Anchor(0.49f, 0.2f)))

    snap.transform.center shouldBe Anchor(0.5f, 0.2f)
    snap.vertical shouldBe true
    snap.horizontal shouldBe false
  }

  @Test
  fun aCentreFurtherOffTheMiddleIsLeftAlone() {
    val transform = OverlayTransform(Anchor(0.47f, 0.53f))

    snapped(transform) shouldBe Snap(transform)
  }

  @Test
  fun eachAxisSnapsOnItsOwn() {
    val snap = snapped(OverlayTransform(Anchor(0.2f, 0.51f)))

    snap.transform.center shouldBe Anchor(0.2f, 0.5f)
    snap.horizontal shouldBe true
    snap.vertical shouldBe false
  }

  @Test
  fun aTurnHalfwayBetweenQuartersIsLeftAlone() {
    val transform = OverlayTransform(Anchor(0.2f, 0.2f), rotationDegrees = 44f)

    snapped(transform) shouldBe Snap(transform)
  }

  @Test
  fun aTurnNearAQuarterSnapsOntoIt() {
    val snap = snapped(OverlayTransform(Anchor(0.2f, 0.2f), rotationDegrees = 88f))

    snap.transform.rotationDegrees shouldBe 90f
    snap.level shouldBe true
  }

  @Test
  fun aTurnJustUnderLevelSnapsToLevelFromBelow() {
    val snap = snapped(OverlayTransform(Anchor(0.2f, 0.2f), scale = 3f, rotationDegrees = -2f))

    snap.transform shouldBe OverlayTransform(Anchor(0.2f, 0.2f), scale = 3f, rotationDegrees = 0f)
  }
}
