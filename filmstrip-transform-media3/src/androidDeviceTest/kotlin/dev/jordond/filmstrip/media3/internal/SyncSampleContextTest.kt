package dev.jordond.filmstrip.media3.internal

import android.media.MediaFormat
import android.net.Uri
import androidx.media3.inspector.MediaExtractorCompat
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe
import dev.jordond.filmstrip.transform.internal.probe.OpeningRead
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * The sync sample a plan snaps a path's trim to, read with and without an installed context.
 *
 * Camel case test names, because dex below version 040, which needs API 30, rejects a space in a method name.
 */
class SyncSampleContextTest {
  private val probe = Media3ExportProbe()
  private val driver = Media3Driver(prober = MediaProber { error("a sync sample is read without probing") })

  @AfterTest
  fun reinstall() {
    FilmstripContext.install(probe.context)
  }

  @Test
  fun aPathReadsTheSameSyncSampleWithoutAContext() =
    runTest {
      val source = probe.fixture(CLIP_LONG)
      val installed =
        withClue("no sync sample before $CUT") {
          driver
            .syncSampleAtOrBefore(source, CUT)
            .shouldBeInstanceOf<OpeningRead.At>()
            .opening
            .shouldNotBeNull()
        }
      withClue("the cut at $CUT already sat on a sync sample, so nothing was read") { (installed < CUT) shouldBe true }

      FilmstripContext.clear()

      driver.syncSampleAtOrBefore(source, CUT).shouldBeInstanceOf<OpeningRead.At>().opening shouldBe installed
    }

  @Test
  fun theSyncSampleIsTheOneMedia3sExtractorSeesOnEveryApiLevel() =
    runTest {
      val file = probe.fixtureFile(CLIP_LONG)
      val expected = compatSyncSampleAtOrBefore(file, CUT)

      val read =
        driver
          .syncSampleAtOrBefore(MediaSource.of(file.path), CUT)
          .shouldBeInstanceOf<OpeningRead.At>()
          .opening
          .shouldNotBeNull()

      read shouldBe expected
    }
}

private fun compatSyncSampleAtOrBefore(
  file: File,
  cut: Duration,
): Duration {
  val extractor = MediaExtractorCompat()
  try {
    extractor.setDataSource(Uri.fromFile(file), 0L)
    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).isVideo() }
    extractor.selectTrack(track)
    extractor.seekTo(cut.inWholeMicroseconds, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
    return extractor.sampleTime.microseconds
  } finally {
    extractor.release()
  }
}

private fun MediaFormat.isVideo(): Boolean = getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true

private const val CLIP_LONG = "android_export_long.mp4"

// Half a second past one of the clip's once a second key frames, so the answer is one read off the container.
private val CUT = 4_500.milliseconds
