package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EnvelopePoint
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.BED_GAIN
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.BED_HZ
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.BED_START
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.FIXTURE_READING
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.PRIMARY_HZ
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.QUIET_READING
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.decodedAudio
import dev.jordond.filmstrip.ffmpeg.support.levelAt
import dev.jordond.filmstrip.ffmpeg.support.onlyClip
import dev.jordond.filmstrip.ffmpeg.support.toneAt
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.ToneAnalysis
import dev.jordond.filmstrip.transform.internal.resolve.curveOver
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Audio levels and fades, read back as decoded samples against the curves the plan resolved.
 */
class FfmpegAudioTest {
  private val fixture = FfmpegExportFixture()

  // The whole point of AudioSpec.AudioOnly is the video track it does not write. Checking that
  // audio survived would pass even if a video track rode along, so the real assertion is that
  // the written file has none.
  @Test
  fun `exports audio only with no video track`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-audio-only", ".mp4").also { it.delete() }
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture.landscape.absolutePath))
          audio(AudioSpec.AudioOnly)
        }

      val finished =
        fixture.filmstrip
          .export(composition, ExportSpec(), MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)

      val success = finished.shouldBeInstanceOf<ExportStatus.Success>()
      success.info.video.shouldBeNull()
      success.info.audio
        .shouldNotBeNull()
        .sampleRate shouldBe 48_000
      withClue("duration was ${success.info.duration}") {
        (success.info.duration in 1_800.milliseconds..2_300.milliseconds).shouldBeTrue()
      }

      // Verified independently of the code under test.
      streamTypes(output.absolutePath) shouldBe listOf("audio")

      output.delete()
    }

  // A ramping gain reaches ffmpeg as an expression rather than a number, so what has to be read
  // back is the whole ramp. Every reading is compared against the same curve the graph was written
  // from, at points inside the fade rather than only at its ends, since both ends read the same
  // under a ramp that runs twice as fast as the one asked for.
  @Test
  fun `ramps a clip fade along the curve the plan resolved`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertRamps(AudioLevel.Envelope(listOf(EnvelopePoint(Duration.ZERO, 0f), EnvelopePoint(FADE, 1f))))
    }

  // A curve this long outruns what one volume node's expression can hold, so it reaches ffmpeg as a
  // chain of them. Reading the ramp back is what proves the chain multiplies to the curve rather
  // than to some coarser one, and that ffmpeg accepted every node in it.
  @Test
  fun `ramps a curve too long for one volume node along the same curve`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val points = (0..CHAINED_POINTS).map { EnvelopePoint(RAMP_STEP * it, it.toFloat() / CHAINED_POINTS) }
      assertRamps(AudioLevel.Envelope(points))
    }

  /**
   * Exports the fixture at [level] and reads the written audio back against the curve [level]
   * resolves to.
   *
   * Every reading is a ratio against the same clip's level at [REFERENCE_AT], scaled by what the
   * curve says the gain there was, so the fixture's own amplitude never enters the assertion.
   */
  private suspend fun assertRamps(level: AudioLevel) {
    val source = MediaSource.of(fixture.landscape.absolutePath)
    val length =
      fixture.filmstrip
        .probe(source)
        .shouldBeInstanceOf<ProbeResult.Success>()
        .info.duration
    val output = File.createTempFile("filmstrip-ramp", ".mp4").also { it.delete() }
    val composition = compositionOf { clip(source) { audio(level) } }

    val finished =
      fixture.filmstrip
        .export(composition, ExportSpec(), MediaSink.of(output.absolutePath))
        .toList()
        .last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    finished.shouldBeInstanceOf<ExportStatus.Success>()

    val curve = level.curveOver(length)
    val samples = decodedAudio(output.absolutePath)
    val reference = samples.levelAt(REFERENCE_AT) / curve.gainAt(REFERENCE_AT)

    RAMP_READINGS.forEach { at ->
      val expected = curve.gainAt(at)
      val measured = samples.levelAt(at) / reference
      withClue("at $at the ramp read $measured against $expected from the resolved curve") {
        (abs(measured - expected) <= ENVELOPE_DRIFT).shouldBeTrue()
      }
    }

    output.delete()
  }

  // The bed from the test above, ramped at both ends by the track builder's own fadeIn and fadeOut
  // rather than by an envelope written out by hand. The track does not loop, so the two ramps sit
  // inside one pass. What loops is covered next door, and what ramps is covered here.
  //
  // Volume sits under both fades, so the curve reaching the file is a constant multiplied by a ramp
  // rather than either alone. Each reading is taken inside a ramp rather than at its ends, at a
  // quarter and three quarters along, since the midpoint alone reads the same whether the fade ran
  // forwards or backwards. The two fades are different lengths for the same reason.
  @Test
  fun `ramps a bed in and out along the fades the plan folded`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val primarySource = MediaSource.of(fixture.long.absolutePath)
      val bedSource = MediaSource.of(fixture.bed.absolutePath)
      val composition =
        compositionOf {
          clip(primarySource)
          track(TrackContent.Audio) {
            clip(bedSource)
            audio(AudioLevel.Volume(BED_GAIN))
            startAt(BED_START)
            fadeIn(BED_FADE_IN)
            fadeOut(BED_FADE_OUT)
          }
        }
      val spec = ExportSpec(heightPx = 240)

      val output = File.createTempFile("filmstrip-fade", ".mp4").also { it.delete() }
      val finished =
        fixture.filmstrip
          .export(composition, spec, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      val tracks = fixture.plannedTracks(composition, spec, listOf(primarySource, bedSource))
      val primaryGain = tracks.first().onlyClip.gain
      val bedGain = tracks.last().onlyClip.gain
      // Without this the fades could have been dropped before the plan and every reading below
      // would agree with a flat curve, passing while proving nothing.
      withClue("the folded bed gain never ramps, so there is no fade to read") { bedGain.constant.shouldBeNull() }

      // Where the ramps sit is derived from the bed's own length as the planner resolved it, so the
      // readings follow the fades rather than pinning instants that a different fixture would move.
      val bedLength = bedGain.end - bedGain.start
      val readings =
        listOf(
          BED_FADE_IN * 0.25,
          BED_FADE_IN * 0.75,
          (BED_FADE_IN + bedLength - BED_FADE_OUT) / 2,
          bedLength - BED_FADE_OUT * 0.75,
          bedLength - BED_FADE_OUT * 0.25,
        )

      val written = decodedAudio(output.absolutePath)
      val bedTone = decodedAudio(fixture.bed.absolutePath).toneAt(FIXTURE_READING, BED_HZ)
      val primaryTone = decodedAudio(fixture.long.absolutePath).toneAt(FIXTURE_READING, PRIMARY_HZ)

      readings.forEach { withinBed ->
        val at = BED_START + withinBed
        val expected =
          (bedTone / primaryTone) * (bedGain.gainAt(withinBed) / primaryGain.gainAt(at))
        val measured = written.toneAt(at, BED_HZ) / written.toneAt(at, PRIMARY_HZ)
        withClue("at $withinBed into the bed it read $measured against $expected from the folded curve") {
          (abs(measured - expected) <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
        }
      }

      val before = written.toneAt(QUIET_READING, BED_HZ) / written.toneAt(QUIET_READING, PRIMARY_HZ)
      withClue("the bed read $before at $QUIET_READING, before it starts") {
        (before <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
      }

      output.delete()
    }

  private fun streamTypes(path: String): List<String> {
    val process =
      ProcessBuilder(
        "ffprobe",
        "-v",
        "error",
        "-show_entries",
        "stream=codec_type",
        "-of",
        "csv=p=0",
        path,
      ).start()
    val output = process.inputReader().readText()
    process.waitFor()
    return output.lineSequence().filter { it.isNotBlank() }.toList()
  }

  private companion object {
    // Half of the two-second fixture, so the curve still holds flat for a second afterwards.
    val FADE = 1_000.milliseconds

    // Where the ramp is read back. Quarters of the fade, so what is asserted is the middle of the
    // range as much as its ends, and every one of them lands inside both curves under test.
    val RAMP_READINGS = listOf(250.milliseconds, 500.milliseconds, 750.milliseconds, FADE)

    // Where the reading every other one is scaled by comes from, clear of the fixture's own end.
    val REFERENCE_AT = 1_500.milliseconds

    // Enough pinned points to outrun what one volume node's expression carries, laid close enough
    // together to stay inside the two-second fixture.
    const val CHAINED_POINTS = 120
    val RAMP_STEP = 16.milliseconds

    // How far a reading of the ramp may sit from the curve. What this separates is a fade of the
    // wrong length or shape, which misses by a quarter of full scale at the points asserted.
    const val ENVELOPE_DRIFT = 0.03f

    // Long enough that a measurement window sits well inside either ramp with room to spare, and
    // different from each other so a fade in swapped for a fade out is not the same curve twice.
    // Together they leave the three second bed a stretch at full level between them.
    val BED_FADE_IN = 1_200.milliseconds
    val BED_FADE_OUT = 900.milliseconds
  }
}
