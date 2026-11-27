package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class StoryCompositionTest {
  @Test
  fun theOutputIsNineBySixteenForALandscapeAndAPortraitSource() {
    val crop =
      storyComposition(SOURCE, StoryLook.Original)
        .clips
        .single()
        .effects
        .single()
        .shouldBeInstanceOf<Crop>()

    listOf(Size(1920, 1080), Size(1080, 1920), Size(720, 1280), Size(640, 640)).forEach { source ->
      val kept = crop.retainedRect(source)
      val aspect = source.width * kept.width / (source.height * kept.height)
      aspect shouldBe (STORY_ASPECT.value plusOrMinus 0.001f)
    }
  }

  @Test
  fun aLookIsLoweredOntoTheCompositionAndTheOriginalAddsNothing() {
    storyComposition(SOURCE, StoryLook.Original).effects.shouldBeEmpty()
    StoryLook.entries.filter { it != StoryLook.Original }.forEach { look ->
      storyComposition(SOURCE, look).effects shouldContainExactly look.effects
      look.effects.isEmpty() shouldBe false
    }
  }

  @Test
  fun theLooksStepRoundInBothDirections() {
    StoryLook.entries.last().next() shouldBe StoryLook.entries.first()
    StoryLook.entries.first().previous() shouldBe StoryLook.entries.last()
    StoryLook.entries[1].next().previous() shouldBe StoryLook.entries[1]
  }

  private companion object {
    val SOURCE = MediaSource.of("/tmp/story.mp4")
  }
}
