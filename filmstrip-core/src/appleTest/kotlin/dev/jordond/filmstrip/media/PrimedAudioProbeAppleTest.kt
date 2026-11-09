package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.internal.PlatformProber
import dev.jordond.filmstrip.test.PrimedAudio
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * An MP4 whose AAC track opens on an encoder delay its edit list skips, probed through each kind of source.
 *
 * AVFoundation takes its own priming off the track rather than the delay the edit list names, so the asset's length
 * runs short of what the movie header says the file plays for.
 */
class PrimedAudioProbeAppleTest {
  private val path =
    Path(SystemTemporaryDirectory, "filmstrip-primed-aac-${Random.nextLong(0, Long.MAX_VALUE)}.mp4").also { file ->
      SystemFileSystem.sink(file).buffered().use { it.write(PrimedAudio.mp4()) }
    }

  @AfterTest
  fun removeFile() {
    SystemFileSystem.delete(path, mustExist = false)
  }

  @Test
  fun aPathReportsTheLengthTheMovieHeaderPresents() =
    runTest {
      durationOf(MediaSource.of(path.toString())) shouldBe PrimedAudio.presentedLength
    }

  @Test
  fun aFileUriReportsTheLengthTheMovieHeaderPresents() =
    runTest {
      durationOf(MediaSource.ofUri("file://$path")) shouldBe PrimedAudio.presentedLength
    }

  @Test
  fun bytesReportTheLengthTheMovieHeaderPresents() =
    runTest {
      durationOf(MediaSource.ofBytes(PrimedAudio.mp4(), FormatHint.Mp4)) shouldBe PrimedAudio.presentedLength
    }

  private suspend fun durationOf(source: MediaSource) =
    withClue("probing $source") {
      PlatformProber()
        .probe(source)
        .shouldBeInstanceOf<ProbeResult.Success>()
        .info.duration
    }
}
