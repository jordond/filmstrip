package dev.jordond.filmstrip.media3

import android.os.Build
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.videoSlotsOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A clip at speed exported through media3: how long the file runs, where its frames land and whether the audio kept its
 * pitch. media3 duplicates no frame, so a slow clip's frames sit as far apart as its speed puts them, and its video
 * still ends on the last slot the clip covers.
 */
class AndroidClipSpeedMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aClipInTheMiddleOfTheRangeHoldsItsLengthAndPitch() =
    runTest(timeout = TIMEOUT) {
      assertSpeed(0.7, SHORT, 2.seconds)
      assertSpeed(1.5, SHORT, 2.seconds)
    }

  @Test
  fun aClipAtTheSlowEndOfTheRangeHoldsItsLengthAndPitch() =
    runTest(timeout = TIMEOUT) {
      assertSpeed(CLIP_SPEED_RANGE.start, SHORT, 500.milliseconds)
    }

  @Test
  fun aClipAtTheFastEndOfTheRangeHoldsItsLengthAndPitch() =
    runTest(timeout = TIMEOUT) {
      assertSpeed(CLIP_SPEED_RANGE.endInclusive, LONG, 12.seconds)
    }

  // The primary at speed ends well inside the bed, and its last frame is what fills the rest.
  @Test
  fun aHeldTailUnderAClipAtSpeedRunsToTheEndOfTheBed() =
    runTest(timeout = TIMEOUT) {
      val primary = Clip(probe.fixture(SHORT), TimeRange.of(Duration.ZERO, 1.seconds), speed = 1.5)
      val bed = Track(listOf(Clip(probe.fixture(BED))), content = TrackContent.Audio)
      val edit = Composition(listOf(Track(listOf(primary)), bed))

      val file = exported(edit)
      val times = videoSampleTimes(file)
      val written = probe.infoOf(MediaSource.of(file.path)).duration

      // media3 writes no frame over a tail, so the last one sits at the end of the clip and the file runs to the bed's.
      withClue("the last frame sat at ${times.last()} of a clip of ${primary.duration}") {
        ((times.last() - checkNotNull(primary.duration)).absoluteValue <= TAIL_TOLERANCE).shouldBeTrue()
      }
      withClue("the file ran $written under a three second bed") {
        ((written - BED_LENGTH).absoluteValue <= TAIL_TOLERANCE).shouldBeTrue()
      }
    }

  // A speed's estimate would pile up from seam to seam, so the last one is where a missing pin shows most.
  @Test
  fun aSequenceOfClipsAtSpeedKeepsEverySeamOnItsSharedTime() =
    runTest(timeout = TIMEOUT) {
      val speeds = listOf(0.7, 1.5, 0.7, 1.5)
      val clips =
        speeds.mapIndexed { index, speed ->
          val fixture = if (index % 2 == 0) SHORT else BED
          Clip(probe.fixture(fixture), TimeRange.of(Duration.ZERO, 1.seconds), speed = speed)
        }

      val file = exported(Composition(listOf(Track(clips))))
      val audio = decodeAudio(file)

      var opens = Duration.ZERO
      speeds.dropLast(1).forEachIndexed { index, speed ->
        opens += outputLengthOf(1.seconds, speed)
        val incomingHz = if (index % 2 == 0) BED_HZ else PRIMARY_HZ
        val outgoingHz = if (index % 2 == 0) PRIMARY_HZ else BED_HZ
        val found = seamOf(audio, opens, incomingHz, outgoingHz)
        withClue("seam $index landed at $found against $opens") {
          ((found - opens).absoluteValue <= SEAM_TOLERANCE).shouldBeTrue()
        }
      }
      assertAudioLength(audio, opens + outputLengthOf(1.seconds, speeds.last()))
      file.delete()
    }

  // Where the incoming tone takes over from the outgoing one, walking a short window across the expected time.
  private fun seamOf(
    audio: DecodedAudio,
    expected: Duration,
    incomingHz: Int,
    outgoingHz: Int,
  ): Duration {
    var at = expected - SEAM_SEARCH
    while (at < expected + SEAM_SEARCH) {
      if (audio.amplitudeOver(at, SEAM_WINDOW, incomingHz) > audio.amplitudeOver(at, SEAM_WINDOW, outgoingHz)) {
        return at + SEAM_WINDOW / 2
      }
      at += SEAM_STEP
    }
    throw AssertionError("no seam within $SEAM_SEARCH of $expected")
  }

  private suspend fun assertSpeed(
    speed: Double,
    fixture: String,
    sourceLength: Duration,
  ) {
    val clip = Clip(probe.fixture(fixture), TimeRange.of(Duration.ZERO, sourceLength), speed = speed)
    val length = outputLengthOf(sourceLength, speed)

    val file = exported(Composition(listOf(Track(listOf(clip)))))

    val times = videoSampleTimes(file)
    val ends = videoTrackLength(file)
    val lastSlotEnds = outputSlotTime(videoSlotsOf(length, OUTPUT_RATE), OUTPUT_RATE)
    withClue("speed $speed video ran to $ends of a clip whose slots end at $lastSlotEnds") {
      ((ends - lastSlotEnds).absoluteValue <= TRACK_END_TOLERANCE).shouldBeTrue()
    }
    // media3's limit: nothing duplicates a frame, so a slow clip's frames sit as far apart as its speed puts them.
    times.zipWithNext { earlier, later -> later - earlier }.forEach { gap ->
      withClue("speed $speed left a gap of $gap between frames") {
        (gap <= maxOf(SOURCE_FRAME / speed, OUTPUT_FRAME + SOURCE_FRAME / speed) + SPARSE_TOLERANCE).shouldBeTrue()
      }
    }

    val audio = decodeAudio(file)
    assertAudioLength(audio, length)
    // Sonic keeps the pitch but joins its pieces out of phase at the top of the range, so a single tone's amplitude
    // falls
    // there while the level holds. The dominant frequency is what says the pitch stayed.
    val middle = length / 2 - TONE_WINDOW / 2
    val dominant = (MIN_HZ..MAX_HZ step HZ_STEP).maxBy { audio.amplitudeOver(middle, TONE_WINDOW, it) }
    withClue("speed $speed peaked at $dominant Hz over a source tone of $PRIMARY_HZ Hz") {
      dominant shouldBe PRIMARY_HZ
    }
    file.delete()
  }

  // The PCM is pinned to the length, and the AAC encoder's limit is what it adds to that: it rounds up to a whole
  // access unit and then flushes some of another, the same way a clip at speed 1 runs over. On API 30 it flushes
  // nothing, so the file holds the whole access units of that PCM less the priming the decoder skips, which
  // AacEncoderFlushMediaTest pins without media3.
  private fun assertAudioLength(
    audio: DecodedAudio,
    length: Duration,
  ) {
    withClue("wrote ${audio.duration} of audio for a length of $length") {
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
        val units = audioFramesOf(length, audio.sampleRate) / AAC_UNIT_FRAMES
        val written = 1.seconds * (units * AAC_UNIT_FRAMES - AAC_PRIMING_FRAMES).toDouble() / audio.sampleRate
        ((audio.duration - written).absoluteValue <= AUDIO_TOLERANCE).shouldBeTrue()
      } else {
        val over = audio.duration - length
        (over >= -AUDIO_TOLERANCE && over <= AAC_TAIL + AUDIO_TOLERANCE).shouldBeTrue()
      }
    }
  }

  private suspend fun exported(edit: Composition): File {
    val statuses =
      withContext(Dispatchers.Default) { probe.filmstrip.export(edit, ExportSpec(), MediaSink.temporary()).toList() }
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) throw AssertionError("export failed: ${finished.error.message}")
    val written = finished.shouldBeInstanceOf<ExportStatus.Success>().output
    return File(written.shouldBeInstanceOf<MediaSink.Path>().path)
  }

  private companion object {
    val TIMEOUT = 3.minutes
    const val OUTPUT_RATE = 30
    const val SHORT = "android_export_a.mp4"
    const val LONG = "android_export_long.mp4"
    const val BED = "tone_bed_880.mp4"
    val BED_LENGTH = 3.seconds
    val SOURCE_FRAME = 1.seconds / 30
    val OUTPUT_FRAME = 1.seconds / OUTPUT_RATE
    val SPARSE_TOLERANCE = 2.milliseconds
    val TAIL_TOLERANCE = 100.milliseconds
    val AUDIO_TOLERANCE = 1.milliseconds

    // The container's own time scale, which the track's length is rounded to.
    val TRACK_END_TOLERANCE = 1.milliseconds
    val AAC_TAIL = 1.seconds * 1_536 / 48_000
    const val AAC_UNIT_FRAMES = 1_024L
    const val AAC_PRIMING_FRAMES = 1_600L
    val SEAM_TOLERANCE = 10.milliseconds
    val TONE_WINDOW = 100.milliseconds
    const val PRIMARY_HZ = 440
    const val BED_HZ = 880
    val SEAM_SEARCH = 200.milliseconds
    val SEAM_WINDOW = 10.milliseconds
    val SEAM_STEP = 1.milliseconds
    const val MIN_HZ = 100
    const val MAX_HZ = 2_000
    const val HZ_STEP = 20
  }
}
