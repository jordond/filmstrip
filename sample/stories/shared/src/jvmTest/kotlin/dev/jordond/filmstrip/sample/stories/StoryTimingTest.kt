package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.OverlayTime
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.sample.visibleWindow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class StoryTimingTest {
  @Test
  fun anElementPartwayIntoItsFadeReadsTheOpacityTheAnimationGives() {
    val time = storyOverlayTime(playhead = 3.seconds, window = TimeRange(2.seconds, 8.seconds), duration = 10.seconds)
    val animation = fade(inDuration = 2.seconds)

    time shouldBe OverlayTime(elapsed = 1.seconds, remaining = 5.seconds, composition = 3.seconds)
    animation.frameAt(time).opacity shouldBe animation.frameAt(OverlayTime(1.seconds, 5.seconds, 3.seconds)).opacity
    animation.frameAt(time).opacity shouldBe 0.5f
  }

  @Test
  fun anElementWithNoWindowCountsFromTheStartOfTheStoryToItsEnd() {
    storyOverlayTime(playhead = 4.seconds, window = null, duration = 10.seconds) shouldBe
      OverlayTime(elapsed = 4.seconds, remaining = 6.seconds, composition = 4.seconds)
  }

  @Test
  fun aWindowRunningPastTheStoryEndsWhereTheStoryDoes() {
    storyOverlayTime(playhead = 9.seconds, window = TimeRange(8.seconds, 30.seconds), duration = 10.seconds) shouldBe
      OverlayTime(elapsed = 1.seconds, remaining = 1.seconds, composition = 9.seconds)
  }

  @Test
  fun aPlayheadOffTheWindowReadsAsItsNearerEnd() {
    val window = TimeRange(2.seconds, 8.seconds)

    storyOverlayTime(1.seconds, window, 10.seconds).elapsed shouldBe 0.seconds
    storyOverlayTime(9.seconds, window, 10.seconds).remaining shouldBe 0.seconds
  }

  @Test
  fun aWindowOutsideThePlayheadHidesAnUnselectedElement() {
    val element = StoryElement(0, StoryContent.Caption("Late"), timed = true, startSeconds = 2f, endSeconds = 4f)

    element.showsAt(1.seconds, selected = false) shouldBe false
    element.showsAt(3.seconds, selected = false) shouldBe true
    element.showsAt(4.seconds, selected = false) shouldBe false
    element.showsAt(1.seconds, selected = true) shouldBe true
  }

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
