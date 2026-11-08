package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration

/**
 * One export through WebCodecs in a real browser on every push, a synthesised clip planned and exported through the
 * public facade with its size and duration read back.
 *
 * The clip has no audio and the export removes it, so no AAC encoder is needed. No frame is read back, so the pixels
 * a software WebGL renderer draws are never compared.
 */
class BrowserExportSmokeTest {
  @Test
  fun bytesPlanAndExport() =
    runTest {
      val bytes = makeClip()
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition)

      val video = success.info.video.shouldNotBeNull()
      video.displaySize.width shouldBe 64
      video.displaySize.height shouldBe 64
      withClue("duration was ${success.info.duration}") { (success.info.duration > Duration.ZERO) shouldBe true }
    }
}
