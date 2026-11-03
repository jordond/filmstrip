package dev.jordond.filmstrip.still

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.SINK_NEEDS_FULL_PATH
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Where an Android still may be written by path, checked against the same refusal media3 gives a video.
 *
 * The host's working directory is writable, so a bare name is only refused here if Android's rule is applied rather
 * than the name being read against wherever the test runs.
 */
class StillWriteAndroidTest {
  private val root: File = Files.createTempDirectory("still-write").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `a bare file name is refused because an app cannot write to the working directory`() =
    runTest {
      val written = writeStill(BYTES, MediaSink.Path(BARE_NAME), StillFormat.Png)

      val refusal =
        written
          .shouldBeInstanceOf<StillWrite.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
      refusal.sink shouldBe BARE_NAME
      refusal.message shouldBe SINK_NEEDS_FULL_PATH
      withClue("a refused write still left a file in the working directory") {
        File(BARE_NAME).exists().shouldBeFalse()
      }
    }

  @Test
  fun `a full path in a real directory is written`() =
    runTest {
      val file = File(root, "still.png")

      val written = writeStill(BYTES, MediaSink.Path(file.path), StillFormat.Png)

      written.shouldBeInstanceOf<StillWrite.Success>().output shouldBe MediaSink.Path(file.path)
      file.readBytes().toList() shouldBe BYTES.toList()
    }

  private companion object {
    const val BARE_NAME = "filmstrip-bare-still.png"
    val BYTES = ByteArray(64) { it.toByte() }
  }
}
