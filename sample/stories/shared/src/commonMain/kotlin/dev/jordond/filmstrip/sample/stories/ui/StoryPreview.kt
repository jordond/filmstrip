package dev.jordond.filmstrip.sample.stories.ui

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.player.VideoPlayer
import dev.jordond.filmstrip.sample.stories.StoryExport
import dev.jordond.filmstrip.still.FrameRenderer

/**
 * What shows the clip under the story.
 *
 * @property player Plays the clip on the stage.
 * @property renderer Draws the frames of the timing bar's strip.
 * @property composition The edit both of them are showing.
 * @property export Exports the story.
 */
internal class StoryPreview(
  val player: VideoPlayer,
  val renderer: FrameRenderer,
  val composition: Composition,
  val export: StoryExport,
) {
  /**
   * Pauses a playing clip and plays a paused one.
   */
  fun togglePlay() {
    if (player.state.value.playWhenReady) player.pause() else player.play()
  }
}
