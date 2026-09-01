package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.compositionOf
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration

/**
 * The single-source [frame] and [still] overloads, over the same thumbnail source double
 * [StillFacadeTest] uses.
 *
 * Each proves delegation rather than re-testing rendering or encoding: a bare source produces the
 * same result as the equivalent hand-built single-clip composition.
 */
class SourceOverloadsTest {
  @Test
  fun `frame(source) matches frame(composition) built from the same source`() =
    runTest {
      val source = SolidThumbnailSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      val fromSource = filmstrip.frame(SOURCE, Duration.ZERO, HEIGHT)
      val fromComposition = filmstrip.frame(compositionOf { clip(SOURCE) }, Duration.ZERO, HEIGHT)

      assertIs<FrameResult.Success>(fromSource).image.toRgba8888() shouldBe
        assertIs<FrameResult.Success>(fromComposition).image.toRgba8888()
    }

  @Test
  fun `still(source) matches still(composition) built from the same source`() =
    runTest {
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> SolidThumbnailSource() } }
      val spec = StillSpec(format = StillFormat.Png)
      val fromSourceTarget = File(scratch(), "from-source.png")
      val fromCompositionTarget = File(scratch(), "from-composition.png")

      val fromSource = filmstrip.still(SOURCE, Duration.ZERO, MediaSink.Path(fromSourceTarget.path), spec)
      val fromComposition =
        filmstrip.still(
          compositionOf { clip(SOURCE) },
          Duration.ZERO,
          MediaSink.Path(fromCompositionTarget.path),
          spec,
        )

      assertIs<StillResult.Success>(fromSource)
      assertIs<StillResult.Success>(fromComposition)
      fromSourceTarget.readBytes() shouldBe fromCompositionTarget.readBytes()
    }

  private fun scratch(): File = createTempDirectory("filmstrip-source-overloads").toFile().apply { deleteOnExit() }

  private companion object {
    const val HEIGHT = 60

    val SOURCE: MediaSource = MediaSource.of("still.mp4")
  }
}
