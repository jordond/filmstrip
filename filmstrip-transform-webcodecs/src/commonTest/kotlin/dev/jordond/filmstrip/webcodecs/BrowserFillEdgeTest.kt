package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.resolve.clipFillsFrame
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Where the browser paints bars for a contained clip, which has to be exactly where the shared test says the clip
 * leaves them.
 */
class BrowserFillEdgeTest {
  // Against a 64 by 36 frame, a 384 by 218 clip falls 0.59 of a pixel short and a 384 by 220 one 1.16 pixels, so the
  // two sit either side of the line. The fixtures stay small since their encoder is pinned to level 3.1.
  @Test
  fun barsLandWhereTheSharedTestPutsThem() =
    runTest {
      listOf(Size(384, 218), Size(384, 220)).forEach { second ->
        val composition =
          EditComposition(
            tracks =
              listOf(
                Track(
                  listOf(
                    Clip(MediaSource.Bytes(makeClip(OUTPUT.width, OUTPUT.height, frames = FRAMES) { _, _ -> GREY })),
                    Clip(MediaSource.Bytes(makeClip(second.width, second.height, frames = FRAMES) { _, _ -> GREY })),
                  ),
                ),
              ),
            audio = AudioSpec.Remove,
            fill = Fill.Blurred(),
          )

        renderOf(composition).clips.last().hasBars shouldBe !clipFillsFrame(second, OUTPUT)
      }
    }

  private companion object {
    val OUTPUT = Size(64, 36)
    val GREY = Rgb(128, 128, 128)
    const val FRAMES = 2
  }
}
