package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.sample.visibleWindow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StoryTimingTest {
  @Test
  fun anElementReadsItsWindowThroughTheSharedHelper() {
    val timed = StoryElement(0, StoryContent.Caption("Timed"), timed = true, startSeconds = 0.4f, endSeconds = 1.5f)

    timed.window shouldBe visibleWindow(true, 0.4f, 1.5f)
    timed.copy(timed = false).window.shouldBeNull()
  }

  @Test
  fun everyEntranceButNoneCarriesAnAnimationAndOnlyCaptionsHaveOne() {
    StoryEntrance.None.animation.shouldBeNull()
    StoryEntrance.entries.filter { it != StoryEntrance.None }.forEach { it.animation.shouldNotBeNull() }
    StoryElement(0, StoryContent.Caption("In", entrance = StoryEntrance.Fade)).animation shouldBe
      StoryEntrance.Fade.animation
    StoryElement(1, StoryContent.Sticker(StoryStickerKind.Star)).animation.shouldBeNull()
  }
}
