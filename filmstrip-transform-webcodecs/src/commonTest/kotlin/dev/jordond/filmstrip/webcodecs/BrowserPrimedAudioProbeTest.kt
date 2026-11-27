package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.movieLengthOf
import dev.jordond.filmstrip.test.PrimedAudio
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Probes an MP4 whose AAC track opens on an encoder delay its edit list skips, and checks the browser reports the
 * length its movie header gives. The same track written as a fragmented file reports the length core reads off its
 * fragments.
 */
class BrowserPrimedAudioProbeTest {
  @Test
  fun aPrimedAudioFileReportsTheLengthItsMovieHeaderGives() =
    runTest {
      val probed = BrowserProber().probe(MediaSource.ofBytes(PrimedAudio.mp4(), FormatHint.Mp4))

      probed.shouldBeInstanceOf<ProbeResult.Success>().info.duration shouldBe PrimedAudio.presentedLength
    }

  @Test
  fun aFragmentedFileWithAnEditReportsTheLengthItsFragmentsPresent() =
    runTest {
      assertProbedAtSharedLength(PrimedAudio.fragmentedMp4())
    }

  @Test
  fun aFragmentedFileWithNoEditReportsEveryTickOfItsFragments() =
    runTest {
      assertProbedAtSharedLength(PrimedAudio.fragmentedMp4(edit = false))
    }

  private suspend fun assertProbedAtSharedLength(bytes: ByteArray) {
    val shared =
      movieLengthOf(bytes.size.toLong()) { start, count ->
        bytes.copyOfRange(start.toInt(), minOf(bytes.size, start.toInt() + count))
      }.shouldNotBeNull()

    val probed = BrowserProber().probe(MediaSource.ofBytes(bytes, FormatHint.Mp4))

    probed.shouldBeInstanceOf<ProbeResult.Success>().info.duration shouldBe shared
  }
}
