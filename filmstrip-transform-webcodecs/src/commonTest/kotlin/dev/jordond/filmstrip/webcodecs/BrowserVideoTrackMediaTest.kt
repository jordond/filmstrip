package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Which video track of a source with two of them the browser reads.
 */
class BrowserVideoTrackMediaTest {
  // The second track is the flagged default, so a reader that picks by disposition reads its size instead.
  @Test
  fun theProbeReadsTheFirstVideoTrackEvenWhenALaterOneIsTheDefault() =
    runTest {
      val source = MediaSource.Bytes(makeClipWithVideoTracks(listOf(FIRST, SECOND), defaultIndex = 1))

      val info = BrowserProber().probe(source).shouldBeInstanceOf<ProbeResult.Success>().info

      info.video.shouldNotBeNull().codedSize shouldBe FIRST
    }

  private companion object {
    val FIRST = Size(64, 48)
    val SECOND = Size(96, 64)
  }
}
