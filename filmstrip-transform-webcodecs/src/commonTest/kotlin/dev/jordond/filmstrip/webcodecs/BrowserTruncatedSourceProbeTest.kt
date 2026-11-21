package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.PrimedAudio
import dev.jordond.filmstrip.test.UnreadableCase
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Probes an MP4 cut off part way through its media data and checks the browser refuses it the way every prober does.
 */
class BrowserTruncatedSourceProbeTest {
  @Test
  fun aFileCutShortOfItsLastSampleIsRefused() =
    runTest {
      val cut = UnreadableCase.TruncatedMdat.bytesFrom(PrimedAudio.mp4()).shouldNotBeNull()
      val source = MediaSource.ofBytes(cut, FormatHint.Mp4)
      val refusal = UnreadableCase.TruncatedMdat.refusalAt(source, cut)

      val probed = BrowserProber().probe(source)

      val failure = probed.shouldBeInstanceOf<ProbeResult.Failure>()
      failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldBe refusal
    }
}
