package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class OverlayDslTest {
  @Test
  fun anImageOverlayAtARangeIsTheOverlayVisibleDuringThatRange() {
    val logo = ImageSource.Path("/fixtures/logo.png")

    val ranged = EffectsBuilder().imageOverlay(logo, Corner.TopEnd, at = 1.seconds..3.seconds, opacity = 0.5f).build()
    val timed =
      EffectsBuilder()
        .imageOverlay(logo, Corner.TopEnd, opacity = 0.5f, visibleDuring = TimeRange.of(1.seconds, 3.seconds))
        .build()

    ranged shouldBe timed
  }
}
