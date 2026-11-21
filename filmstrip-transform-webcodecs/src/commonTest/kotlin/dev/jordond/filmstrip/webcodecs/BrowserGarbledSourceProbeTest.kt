package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.UnreadableCase
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.measureTimedValue

/**
 * Probes an MP4 whose media data was overwritten with noise and checks the browser refuses it the way every prober
 * does, while the same file left whole still probes.
 */
class BrowserGarbledSourceProbeTest {
  @Test
  fun aFileWhoseFirstFrameDoesNotDecodeIsRefused() =
    runTest {
      val garbled = UnreadableCase.GarbledMdat.bytesFrom(makeClip()).shouldNotBeNull()
      val source = MediaSource.ofBytes(garbled, FormatHint.Mp4)
      val refusal = UnreadableCase.GarbledMdat.refusalAt(source, garbled)

      val (probed, took) = measureTimedValue { BrowserProber().probe(source) }
      println("garbled probe took $took")

      val failure = probed.shouldBeInstanceOf<ProbeResult.Failure>()
      failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldBe refusal
    }

  @Test
  fun theSameFileLeftWholeStillProbes() =
    runTest {
      val source = MediaSource.ofBytes(makeClip(), FormatHint.Mp4)

      val (probed, took) = measureTimedValue { BrowserProber().probe(source) }
      println("whole probe took $took")

      probed
        .shouldBeInstanceOf<ProbeResult.Success>()
        .info.video
        .shouldNotBeNull()
    }
}
