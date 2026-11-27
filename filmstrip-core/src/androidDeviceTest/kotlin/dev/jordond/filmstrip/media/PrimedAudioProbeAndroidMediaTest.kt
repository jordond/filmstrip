package dev.jordond.filmstrip.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.internal.PlatformProber
import dev.jordond.filmstrip.internal.probe
import dev.jordond.filmstrip.test.PrimedAudio
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Probes an MP4 whose AAC track opens on an encoder delay its edit list skips, through a path, a file URI, a
 * `content://` URI and bytes, and checks each reports the length the movie header gives. The same track written as a
 * fragmented file reports the length core reads off its fragments.
 */
class PrimedAudioProbeAndroidMediaTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val file =
    File(context.cacheDir, "primed-aac-${System.nanoTime()}.mp4").apply { writeBytes(PrimedAudio.mp4()) }
  private val fragmented =
    File(context.cacheDir, "fragmented-aac-${System.nanoTime()}.mp4").apply { writeBytes(PrimedAudio.fragmentedMp4()) }

  @AfterTest
  fun removeFile() {
    file.delete()
    fragmented.delete()
  }

  @Test
  fun aPathReportsTheLengthTheEditListPresents() =
    runTest {
      durationOf(MediaSource.of(file.path)) shouldBe PrimedAudio.presentedLength
    }

  @Test
  fun aUriReportsTheLengthTheEditListPresents() =
    runTest {
      FilmstripContext.install(context)

      durationOf(MediaSource.ofUri(Uri.fromFile(file).toString())) shouldBe PrimedAudio.presentedLength
    }

  @Test
  fun aContentUriReportsTheLengthTheEditListPresents() =
    runTest {
      FilmstripContext.install(context)

      durationOf(MediaSource.ofUri(PrimedAudioProvider.SEEKABLE)) shouldBe PrimedAudio.presentedLength
    }

  // A pipe has no size to walk, so whatever the probe says comes from the retriever alone.
  @Test
  fun aContentUriThatCannotSeekIsLeftToTheRetriever() =
    runTest {
      FilmstripContext.install(context)
      val retrieved = retrieverDuration(PrimedAudioProvider.PIPED)

      val probed = PlatformProber().probe(MediaSource.ofUri(PrimedAudioProvider.PIPED))

      withClue("the retriever read ${retrieved ?: "nothing"}") {
        if (retrieved == null) {
          probed.shouldBeInstanceOf<ProbeResult.Failure>()
        } else {
          probed.shouldBeInstanceOf<ProbeResult.Success>().info.duration shouldBe retrieved.milliseconds
        }
      }
    }

  @Test
  fun bytesReportTheLengthTheEditListPresents() =
    runTest {
      FilmstripContext.install(context)

      durationOf(MediaSource.ofBytes(PrimedAudio.mp4(), FormatHint.Mp4)) shouldBe PrimedAudio.presentedLength
    }

  @Test
  fun aFragmentedPathReportsTheLengthItsFragmentsPresent() =
    runTest {
      durationOf(MediaSource.of(fragmented.path)) shouldBe sharedLengthOf(PrimedAudio.fragmentedMp4())
    }

  @Test
  fun fragmentedBytesReportTheLengthTheirFragmentsPresent() =
    runTest {
      FilmstripContext.install(context)
      val bytes = PrimedAudio.fragmentedMp4(edit = false)

      durationOf(MediaSource.ofBytes(bytes, FormatHint.Mp4)) shouldBe sharedLengthOf(bytes)
    }

  private suspend fun sharedLengthOf(bytes: ByteArray) =
    movieDurationOf(bytes.size.toLong()) { start, count ->
      bytes.copyOfRange(start.toInt(), minOf(bytes.size, start.toInt() + count))
    }

  private fun retrieverDuration(uri: String): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, Uri.parse(uri))
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (unreadable: RuntimeException) {
      null
    } finally {
      retriever.release()
    }
  }

  private suspend fun durationOf(source: MediaSource) =
    withClue("probing $source") {
      PlatformProber()
        .probe(source)
        .shouldBeInstanceOf<ProbeResult.Success>()
        .info.duration
    }
}
