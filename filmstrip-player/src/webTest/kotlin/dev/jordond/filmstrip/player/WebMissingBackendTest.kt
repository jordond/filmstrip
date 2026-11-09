package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.contractTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.test.Test

/**
 * A browser preview on a `Filmstrip` with `playerBackend()` and nothing to read its source with.
 */
class WebMissingBackendTest {
  @Test
  fun `a uri preview with no webcodecs backend reports the artifact to add`() =
    contractTest {
      val source = MediaSource.ofUri("https://example.test/clip.mp4")
      val composition = Composition(tracks = listOf(Track(listOf(Clip(source)))))
      val player = Filmstrip { playerBackend() }.preview(composition)

      try {
        val error =
          player.state
            .map { it.status }
            .filterIsInstance<PlaybackStatus.Error>()
            .first()
            .error
            .shouldBeInstanceOf<PlaybackError.BackendMissing>()
        error.artifact shouldBe WEB_CODECS_ARTIFACT
        error.message shouldContain "webCodecsBackend()"
      } finally {
        player.close()
      }
    }

  private companion object {
    // Published coordinate, core's own constant is internal to it.
    const val WEB_CODECS_ARTIFACT = "dev.jordond.filmstrip:filmstrip-transform-webcodecs"
  }
}
