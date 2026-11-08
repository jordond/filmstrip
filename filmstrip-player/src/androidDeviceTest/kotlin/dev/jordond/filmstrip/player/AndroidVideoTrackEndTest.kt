package dev.jordond.filmstrip.player

import android.media.MediaExtractor
import android.media.MediaFormat
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.transform.internal.videoSlotsOf
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where a media3 file's video track ends when a longer audio track runs past the picture.
 *
 * media3 writes no frame over the slots the audio runs past the primary, so the last frame is held there by the video
 * track's own length. That length is the slots the composition covers, so it ends on the frame grid rather than on the
 * composition's own length, which [BED] puts between two slots.
 */
class AndroidVideoTrackEndTest {
  @Test
  fun aVideoTrackUnderLongerAudioEndsOnTheLastSlotTheCompositionCovers() =
    contractTest { _ ->
      val clip = androidFixtureClip()
      val edit =
        EditComposition(
          listOf(
            Track(listOf(Clip(clip, TimeRange.of(Duration.ZERO, 1.seconds)))),
            Track(listOf(Clip(clip, TimeRange.of(Duration.ZERO, BED))), content = TrackContent.Audio),
          ),
        )
      val duration = checkNotNull(edit.duration)
      val gridEnd = outputSlotTime(videoSlotsOf(duration, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)
      withClue("the composition ends on a slot, so either end reads the same") { gridEnd shouldNotBe duration }

      videoTrackLengthOf(androidExportedFile(edit)) shouldBe gridEnd
    }

  private fun videoTrackLengthOf(file: File): Duration {
    val extractor = MediaExtractor().apply { setDataSource(file.path) }
    try {
      val video =
        (0 until extractor.trackCount)
          .map { extractor.getTrackFormat(it) }
          .firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
          ?: fail("the file holds no video")
      return video.getLong(MediaFormat.KEY_DURATION).microseconds
    } finally {
      extractor.release()
    }
  }

  private companion object {
    // A third of a slot past the 36th, at the fixture's rate.
    val BED = 1_210.milliseconds
  }
}
