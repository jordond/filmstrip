package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

  @Test
  fun aFadeInOnlyInsideEffectsGoesToTheOverlayAndAFadeOutOutsideGoesToTheClip() {
    val composition =
      compositionOf {
        clip(MediaSource.of("a.mp4")) {
          effects {
            textOverlay("x", animation = fade(inDuration = 1.seconds))
          }
          fadeOut(2.seconds)
        }
      }

    val clip =
      composition.tracks
        .single()
        .clips
        .single()
    clip.effects
      .single()
      .shouldBeInstanceOf<TextOverlay>()
      .animation shouldBe fade(inDuration = 1.seconds)
    clip.fadeOut shouldBe 2.seconds
  }
}
