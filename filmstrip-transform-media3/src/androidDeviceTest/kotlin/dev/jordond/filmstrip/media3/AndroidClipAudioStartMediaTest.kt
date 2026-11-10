package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Where a source frame lands in a media3 export of a trimmed clip, read off a click.
 *
 * A steady tone is loud from wherever a trim opens, so it cannot say which source frame a clip opened on. The click
 * fixture is silent but for one frame, and that frame belongs at the output time core's own figures give it.
 */
class AndroidClipAudioStartMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aTrimmedClipAtSpeedOnePlacesItsAudioOnTheSharedTime() =
    runTest(timeout = TIMEOUT) {
      assertClickLands(1.0)
    }

  @Test
  fun aTrimmedClipInTheMiddleOfTheSpeedRangePlacesItsAudioOnTheSharedTime() =
    runTest(timeout = TIMEOUT) {
      assertClickLands(0.5)
    }

  @Test
  fun aTrimmedFastClipPlacesItsAudioOnTheSharedTime() =
    runTest(timeout = TIMEOUT) {
      assertClickLands(2.0)
    }

  @Test
  fun aTrimmedClipAtTheSlowEndOfTheRangePlacesItsAudioOnTheSharedTime() =
    runTest(timeout = TIMEOUT) {
      assertClickLands(CLIP_SPEED_RANGE.start)
    }

  @Test
  fun aTrimmedClipAtTheFastEndOfTheRangePlacesItsAudioOnTheSharedTime() =
    runTest(timeout = TIMEOUT) {
      assertClickLands(CLIP_SPEED_RANGE.endInclusive)
    }

  // A uri reads a `#` as the start of a fragment, so a path opened as one would name another file.
  @Test
  fun aTrimmedClipWhoseFileNameReadsAsAUriPlacesItsAudioOnTheSharedTime() =
    runTest(timeout = TIMEOUT) {
      val file = probe.fixtureFile(CLICK).let { it.copyTo(File(it.parentFile, URI_LIKE_NAME), overwrite = true) }
      try {
        assertClickLands(1.0, MediaSource.of(file.path))
      } finally {
        file.delete()
      }
    }

  // media3 exports the audio track its selector picks, the one in the device's language here, which is not the first.
  // The decoy is the same audio relabelled to another rate, so units read off it sit on another grid.
  @Test
  fun aClipFromASourceWithTwoAudioTracksOpensOnTheTrackMedia3Exports() =
    runTest(timeout = TIMEOUT) {
      val click = probe.fixtureFile(CLICK)
      val single = File(click.parentFile, "click_one_audio.mp4")
      val twin = File(click.parentFile, "click_two_audio.mp4")
      try {
        remuxed(click, single, listOf(AudioCopy(deviceLanguage)))
        remuxed(click, twin, listOf(AudioCopy(otherLanguage, DECOY_RATE), AudioCopy(deviceLanguage)))
        val expected = readingsOf(1.0, MediaSource.of(single.path))
        val read = readingsOf(1.0, MediaSource.of(twin.path))
        val off =
          read.zip(expected).filter { (twoTracks, oneTrack) ->
            abs(twoTracks.frames - oneTrack.frames) >
              CLICK_TOLERANCE
          }
        withClue(
          "two audio tracks placed the click at ${read.joinToString()}, one track at ${expected.joinToString()}",
        ) {
          off.shouldBeEmpty()
        }
      } finally {
        single.delete()
        twin.delete()
      }
    }

  private suspend fun assertClickLands(
    speed: Double,
    source: MediaSource = probe.fixture(CLICK),
  ) {
    val readings = readingsOf(speed, source)
    // Sonic's limit: it stretches by repeating or dropping whole pitch periods, and a frame moves with the start of its
    // period. Slowed, the period plays 1 / speed times, so the frame's first copy can land up to 1 / speed - 1 periods
    // early. Sped up, a dropped period can move it by one.
    val period = readings.first().sampleRate / SONIC_MINIMUM_PITCH_HZ
    val tolerance = if (speed == 1.0) CLICK_TOLERANCE.toDouble() else period * maxOf(1.0, 1 / speed - 1)
    val off = readings.filter { abs(it.frames) > tolerance }
    withClue("at speed $speed the click landed off its shared time by ${readings.joinToString()}") {
      off.shouldBeEmpty()
    }
  }

  private suspend fun readingsOf(
    speed: Double,
    source: MediaSource,
  ): List<Reading> =
    TRIM_STARTS.map { start ->
      val clip = Clip(source, TimeRange.of(start, start + TRIM_LENGTH), speed = speed)
      val file = exported(compositionHearing(clip, probe.fixture(CLICK)))
      val audio = decodeAudio(file)
      file.delete()
      val peak = audio.samples.indices.maxBy { abs(audio.samples[it]) }
      val expected = audioFramesOf(outputLengthOf(CLICK_AT - start, speed), audio.sampleRate)
      Reading(start, peak - expected, audio.sampleRate)
    }

  // How far one trim put the click from where it belongs, in output frames.
  private class Reading(
    val start: Duration,
    val frames: Long,
    val sampleRate: Int,
  ) {
    override fun toString(): String = "$frames frames (${frames * 1_000.0 / sampleRate} ms) trimmed from $start"
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
    const val CLICK = "android_click.mp4"
    const val URI_LIKE_NAME = "clip #1.mp4"
    const val DECOY_RATE = 44_100

    // Where the fixture's click sits, partway into an AAC access unit of 1024 frames at 48 kHz.
    val CLICK_AT = 310.milliseconds
    val TRIM_LENGTH = 350.milliseconds

    // Two starts inside the first access unit, two inside a later one, and one on a unit's own start.
    val TRIM_STARTS =
      listOf(Duration.ZERO, 10.milliseconds, 200.milliseconds, 210.milliseconds, 192.milliseconds)

    // The AAC encoder rings around a click, so its peak can move by a few frames each time it is encoded.
    const val CLICK_TOLERANCE = 8

    // The lowest pitch media3's Sonic searches for, which sets the longest period it repeats.
    const val SONIC_MINIMUM_PITCH_HZ = 65
  }
}
