package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * The preview cap and the frame save, on a session with no backend behind it.
 */
class PreviewOptionsTest {
  private val recorder = DiagnosticsRecorder()
  private val state =
    SampleAppState(
      filmstrip = Filmstrip { autoRegister(false) },
      recorder = recorder,
      scope = CoroutineScope(Dispatchers.Unconfined),
    )

  @Test
  fun `a preview nobody capped renders in full and carries the export's spec`() {
    state.options.quality = VideoQuality.Medium

    val config = state.playerConfig()

    config.qualityPolicy shouldBe PreviewQualityPolicy.Full
    config.exportSpec shouldBe state.spec()
  }

  @Test
  fun `a cap reaches the player as a height cap`() {
    state.previewCap = 540

    state
      .playerConfig()
      .qualityPolicy
      .shouldBeInstanceOf<PreviewQualityPolicy.CapHeight>()
      .heightPx shouldBe 540
  }

  @Test
  fun `saving a frame with nothing open does nothing`() {
    state.saveFrame()

    state.frames.saved.shouldBeNull()
    state.frames.failure.shouldBeNull()
    recorder.events.none { it.label.startsWith("frame.") } shouldBe true
  }

  @Test
  fun `a frame that cannot be rendered says why and is logged`() {
    state.onPicked(MediaSource.of("/tmp/clip.mp4"), "clip.mp4")

    state.saveFrame()

    state.frames.saving shouldBe false
    state.frames.saved.shouldBeNull()
    state.frames.failure.shouldNotBeNull()
    recorder.events.count { it.label == "frame.failed" } shouldBe 1
  }

  @Test
  fun `closing the project forgets the last frame save`() {
    state.onPicked(MediaSource.of("/tmp/clip.mp4"), "clip.mp4")
    state.saveFrame()

    state.closeProject()

    state.frames.failure.shouldBeNull()
  }
}
