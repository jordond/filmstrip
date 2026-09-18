package dev.jordond.filmstrip.filekit.compose

import dev.jordond.filmstrip.media.MediaSink
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The browser saver opens no dialog, so the name it reports back is the whole of its behaviour.
 */
class DownloadSinkJvmTest {
  @Test
  fun `an extension is joined onto the name`() {
    downloadSink("clip", "mp4") shouldBe MediaSink.Path("clip.mp4")
  }

  @Test
  fun `no extension leaves the name alone`() {
    downloadSink("clip", null) shouldBe MediaSink.Path("clip")
  }

  @Test
  fun `a blank extension leaves the name alone`() {
    downloadSink("clip", "") shouldBe MediaSink.Path("clip")
  }
}
