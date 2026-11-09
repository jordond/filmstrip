package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.PrimedAudio
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Probes an MP4 whose AAC track opens on an encoder delay its edit list skips, and checks the browser reports the
 * length its movie header gives.
 */
class BrowserPrimedAudioProbeTest {
  @Test
  fun aPrimedAudioFileReportsTheLengthItsMovieHeaderGives() =
    runTest {
      val probed = BrowserProber().probe(MediaSource.ofBytes(PrimedAudio.mp4(), FormatHint.Mp4))

      probed.shouldBeInstanceOf<ProbeResult.Success>().info.duration shouldBe PrimedAudio.presentedLength
    }
}
