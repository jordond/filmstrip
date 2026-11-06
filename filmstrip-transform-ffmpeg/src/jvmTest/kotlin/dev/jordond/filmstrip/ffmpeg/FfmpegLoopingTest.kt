package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportPlan
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
import dev.jordond.filmstrip.ffmpeg.support.onlyClip
import dev.jordond.filmstrip.ffmpeg.support.toneAt
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.LoopingCases
import dev.jordond.filmstrip.test.ToneAnalysis
import dev.jordond.filmstrip.transform.internal.passesCovering
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.runLengthOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
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
import kotlin.time.Duration.Companion.seconds

/**
 * Looping tracks, with every pass of a looping bed or clip measured against the gain the plan laid.
 */
class FfmpegLoopingTest {
  private val fixture = FfmpegExportFixture()

  // A second track is a mix only once both sources reach the file at their own levels. The bed
  // carries the one tone no other fixture does, so a reading at 880 Hz belongs to it and a reading
  // at 440 Hz belongs to the primary, and neither is told apart by amplitude.
  //
  // Everything is asserted as a ratio of the two tones, because an AAC round trip attenuates the
  // whole mix and an absolute level would be pinning the codec rather than the gain. The window in
  // the middle sits past the bed's own length, so a bed that ignored startAt and a bed that played
  // once instead of looping both fail there while reading correctly at the start.
  @Test
  fun `mixes a looping bed under the primary at the level the plan folded`() =
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
            looping()
          }
        }
      val spec = ExportSpec(heightPx = 240)

      val output = File.createTempFile("filmstrip-bed", ".mp4").also { it.delete() }
      val finished =
        fixture.filmstrip
          .export(composition, spec, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      val tracks = fixture.plannedTracks(composition, spec, listOf(primarySource, bedSource))
      val primaryGain = tracks.first().onlyClip.gain

      // The plan holds one clip per pass, so the pass that covers the reading and how far into it
      // that reading sits both come off the laid list rather than off the fixture's own length.
      val laid = tracks.last().clips
      val pass = laid.indexOfLast { it.span.start <= MIXED_READING }
      withClue("$MIXED_READING lands in pass ${pass + 1} of a ${laid.size} pass bed") { (pass >= 1).shouldBeTrue() }
      val bedGain = laid[pass].gain
      val withinBed = MIXED_READING - laid[pass].span.start

      val written = decodedAudio(output.absolutePath)
      // The fixtures' own tones, so how loud either source was recorded divides out of the ratio.
      val bedTone = decodedAudio(fixture.bed.absolutePath).toneAt(FIXTURE_READING, BED_HZ)
      val primaryTone = decodedAudio(fixture.long.absolutePath).toneAt(FIXTURE_READING, PRIMARY_HZ)

      val expected =
        (bedTone / primaryTone) * (bedGain.gainAt(withinBed) / primaryGain.gainAt(MIXED_READING))
      val measured = written.toneAt(MIXED_READING, BED_HZ) / written.toneAt(MIXED_READING, PRIMARY_HZ)
      withClue("at $MIXED_READING the bed read $measured against $expected from the folded gain") {
        (abs(measured - expected) <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
      }

      // Before the track starts the bed contributes nothing, so its reading is held to the same
      // tolerance every other one is, against a gain of zero.
      val before = written.toneAt(QUIET_READING, BED_HZ) / written.toneAt(QUIET_READING, PRIMARY_HZ)
      withClue("the bed read $before at $QUIET_READING, before it starts") {
        (before <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
      }

      output.delete()
    }

  // The test above reads one instant a pass past the bed's end. This one reads every pass the plan
  // laid, including the one it cut, because a graph can write the first repeat and still drop the
  // rest or run past the composition.
  //
  // The bed's trim, the track's offset and the composition's length divide into each other nowhere,
  // so the last pass is always cut and no reading sits on a pass boundary. The track's fade covers
  // the whole run rather than restarting each pass, and the readings a quarter and three quarters
  // along it are what separate the two: a fade replayed per pass opens the second pass at silence
  // where this one opens well up the ramp.
  @Test
  fun `measures every pass of a looping bed against the gain the plan laid`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val primarySource = MediaSource.of(fixture.long.absolutePath)
      val bedSource = MediaSource.of(fixture.bed.absolutePath)
      val composition =
        compositionOf {
          clip(primarySource) { trim(TimeRange.of(Duration.ZERO, LoopingCases.PRIMARY_RUN)) }
          track(TrackContent.Audio) {
            clip(bedSource) { trim(LoopingCases.BED_TRIM) }
            startAt(LoopingCases.BED_START)
            fadeIn(LoopingCases.TRACK_FADE)
            looping()
          }
        }
      val spec = ExportSpec(heightPx = 240)

      val output = File.createTempFile("filmstrip-loop-passes", ".mp4").also { it.delete() }
      val plan = fixture.plannedRun(composition, spec)
      val written = fixture.exported(plan, output)

      val tracks = fixture.plannedTracks(composition, spec, listOf(primarySource, bedSource))
      val primaryGain = tracks.first().onlyClip.gain
      val laid = tracks.last().clips
      // Asserted before a sample is measured, so a schedule that laid the wrong passes fails by
      // name rather than as a level that missed.
      tracks.last().assertLaysPasses(LoopingCases.BED_LENGTHS, plan.duration)
      assertRunsFor(plan, written)
      assertFadeCarriesAcross(laid)

      val samples = decodedAudio(output.absolutePath)
      val fixtures = fixtureRatio()

      LoopingCases.BED_READINGS.forEach { (pass, into) ->
        assertBedLevel(samples, fixtures, laid[pass], primaryGain, into, "pass ${pass + 1}")
      }

      // Before the track starts the bed contributes nothing, held to the same tolerance every other
      // reading is against a gain of zero.
      val quiet = LoopingCases.BEFORE_BED
      val before = samples.bedAgainstPrimary(quiet)
      withClue("the bed read $before at $quiet, before it starts") {
        (before <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
      }

      output.delete()
    }

  // Two clips on one looping track, told apart by a clip Volume rather than by frequency, since
  // there is only one bed tone to go round. Every pass lays A then B, so a reading inside B says
  // the graph kept the two in order and cut the run where the plan did rather than playing each
  // clip on a loop of its own.
  @Test
  fun `keeps two looping clips apart at the levels the plan laid`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val primarySource = MediaSource.of(fixture.long.absolutePath)
      val bedSource = MediaSource.of(fixture.bed.absolutePath)
      val composition =
        compositionOf {
          clip(primarySource) { trim(TimeRange.of(Duration.ZERO, LoopingCases.PRIMARY_RUN)) }
          track(TrackContent.Audio) {
            clip(bedSource) { trim(LoopingCases.LOUD_TRIM) }
            clip(bedSource) {
              trim(LoopingCases.QUIET_TRIM)
              audio(AudioLevel.Volume(LoopingCases.QUIET_VOLUME))
            }
            startAt(LoopingCases.PAIR_START)
            looping()
          }
        }
      val spec = ExportSpec(heightPx = 240)

      val output = File.createTempFile("filmstrip-loop-two", ".mp4").also { it.delete() }
      val plan = fixture.plannedRun(composition, spec)
      val written = fixture.exported(plan, output)

      val tracks = fixture.plannedTracks(composition, spec, listOf(primarySource, bedSource))
      val primaryGain = tracks.first().onlyClip.gain
      val laid = tracks.last().clips
      tracks.last().assertLaysPasses(LoopingCases.PAIR_LENGTHS, plan.duration)
      assertRunsFor(plan, written)

      val samples = decodedAudio(output.absolutePath)
      val fixtures = fixtureRatio()

      // A graph that stopped after the first pass writes silence from here on, which the levels
      // below would read as a gain that missed rather than as a bed that never arrived.
      val (firstPass, firstInto) = LoopingCases.PAIR_READINGS.first()
      val present = samples.bedAgainstPrimary(laid[firstPass].span.start + firstInto)
      withClue("the bed read $present a pass past its own end, where it should still be playing") {
        (present > ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
      }

      LoopingCases.PAIR_READINGS.forEach { (index, into) ->
        val clip = if (laid[index].sourceIndex == 0) "clip A" else "clip B"
        assertBedLevel(samples, fixtures, laid[index], primaryGain, into, "pass ${index / 2 + 1}, $clip")
      }

      output.delete()
    }

  // A looping video primary under a longer audio-only track, which is the case where the loop has
  // to carry pictures as well as samples. Before the passes were laid the graph ran the primary
  // once and wrote the fill from there on, so the frame inside the third pass is as much of the
  // assertion as the tone is.
  @Test
  fun `loops a video primary under a longer audio track`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val bedSource = MediaSource.of(fixture.bed.absolutePath)
      val toneSource = MediaSource.of(fixture.long.absolutePath)
      val composition =
        compositionOf {
          // Written as a track rather than as a primary clip, since only a track carries looping
          // and the builder promotes a leading track to the primary one.
          track {
            clip(bedSource) { trim(LoopingCases.VIDEO_TRIM) }
            looping()
          }
          track(TrackContent.Audio) {
            clip(toneSource) { trim(TimeRange.of(Duration.ZERO, LoopingCases.UNDERLAY_RUN)) }
          }
        }
      val spec = ExportSpec(heightPx = 240)

      val output = File.createTempFile("filmstrip-loop-video", ".mp4").also { it.delete() }
      val plan = fixture.plannedRun(composition, spec)
      val written = fixture.exported(plan, output)

      val tracks = fixture.plannedTracks(composition, spec, listOf(bedSource, toneSource))
      val toneGain = tracks.last().onlyClip.gain
      val laid = tracks.first().clips
      tracks.first().assertLaysPasses(LoopingCases.VIDEO_LENGTHS, plan.duration)
      assertRunsFor(plan, written)

      val samples = decodedAudio(output.absolutePath)
      val fixtures = fixtureRatio()

      LoopingCases.VIDEO_READINGS.forEach { (pass, into) ->
        assertBedLevel(samples, fixtures, laid[pass], toneGain, into, "pass ${pass + 1}")
      }

      // The audio-only track holds one level for the whole composition, so its own tone cannot move
      // across the readings. A track laid only as far as the primary's first pass reads nothing at
      // the later ones, which every ratio above would report as the loop being wrong instead.
      val reference = samples.toneAt(LOOP_VIDEO_REFERENCE, PRIMARY_HZ)
      LoopingCases.VIDEO_READINGS.forEach { (pass, into) ->
        val at = laid[pass].span.start + into
        val held = samples.toneAt(at, PRIMARY_HZ) / reference
        withClue("at $at the audio track read $held of the level it holds at $LOOP_VIDEO_REFERENCE") {
          (abs(held - 1f) <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
        }
      }

      // The picture inside a later pass, which is the bed's own pattern rather than the flat color
      // a composition with nothing laid on it falls back to.
      val (picturePass, pictureInto) = LoopingCases.VIDEO_READINGS.first()
      val pictureAt = laid[picturePass].span.start + pictureInto
      val fixtureSpread =
        fixture.frameAt(fixture.bed.absolutePath, LoopingCases.VIDEO_TRIM.start + pictureInto).spread()
      val spread = fixture.frameAt(output.absolutePath, pictureAt).spread()
      withClue(
        "the frame at $pictureAt spans $spread against the fixture's own $fixtureSpread, so it carries the fill",
      ) {
        (spread >= fixtureSpread * PICTURE_SPREAD_FLOOR).shouldBeTrue()
      }

      output.delete()
    }

  /**
   * Asserts [written] runs for as long as [plan] said it would, to within one frame of the output.
   *
   * A looping track that laid its last pass whole instead of cutting it overruns by the rest of
   * that pass, which is most of a second here, so the band separates the two without pinning how
   * much padding a muxer writes.
   */
  private fun assertRunsFor(
    plan: ExportPlan,
    written: MediaInfo,
  ) {
    val frame =
      1.seconds /
        withClue("the written file names no frame rate") {
          written.video?.frameRate.shouldNotBeNull()
        }.toDouble()
    withClue("the file runs for ${written.duration}, where the plan laid ${plan.duration} and a frame is $frame") {
      (abs((written.duration - plan.duration).inWholeMicroseconds) <= frame.inWholeMicroseconds).shouldBeTrue()
    }
  }

  /**
   * Pins every laid pass of this track by which clip of the track it plays and by the span it holds,
   * against the run [passesCovering] derives for [lengths] over a composition of [duration].
   *
   * Whether the track loops decides how much of that composition it covers, so a track laid down
   * once is held to its own clips rather than to the whole run.
   */
  private fun ResolvedTrack.assertLaysPasses(
    lengths: List<Duration>,
    duration: Duration,
  ) {
    val expected =
      passesCovering(lengths, runLengthOf(looping, start, lengths, duration)).map { pass ->
        Triple(pass.index, start + pass.offset, start + pass.offset + pass.length)
      }
    clips.map { Triple(it.sourceIndex, it.span.start, it.span.endExclusive) } shouldBe expected
  }

  /**
   * Asserts each of [laid] opens on the gain the pass before it closed on, which is what a track
   * fade measured over the whole run does and a fade replayed every pass does not.
   */
  private fun assertFadeCarriesAcross(laid: List<ResolvedClip>) {
    laid.zipWithNext { earlier, later ->
      val closed = earlier.gain.gainAt(earlier.duration)
      val opened = later.gain.gainAt(Duration.ZERO)
      withClue("a pass opened at $opened where the one before it closed at $closed, so the fade restarted") {
        (abs(opened - closed) <= ResolvedGain.PRODUCT_TOLERANCE).shouldBeTrue()
      }
    }
  }

  /**
   * How loud the bed fixture's tone is against the long fixture's, so how loud either was recorded
   * divides out of every reading taken from the mix.
   */
  private fun fixtureRatio(): Float =
    decodedAudio(fixture.bed.absolutePath).toneAt(FIXTURE_READING, BED_HZ) /
      decodedAudio(fixture.long.absolutePath).toneAt(FIXTURE_READING, PRIMARY_HZ)

  /**
   * Asserts the 880 Hz tone at [into] of [clip] reads at that clip's laid gain, scaled by the level
   * [against] holds.
   *
   * Where [into] lands on the composition clock comes off the laid span, so the file is read at the
   * instant the gain was sampled at. [against] is the 440 Hz track's own curve, which every case
   * here leaves flat, and it is read as one number rather than sampled in a time that is not its own.
   */
  private fun assertBedLevel(
    samples: FloatArray,
    fixtures: Float,
    clip: ResolvedClip,
    against: ResolvedGain,
    into: Duration,
    what: String,
  ) {
    withClue("$into runs past $what, which is ${clip.duration} long") { (into < clip.duration).shouldBeTrue() }
    val level = withClue("$what is read against a track whose own gain ramps") { against.constant.shouldNotBeNull() }
    val at = clip.span.start + into
    val expected = fixtures * (clip.gain.gainAt(into) / level)
    val measured = samples.bedAgainstPrimary(at)
    withClue("at $at, $into into $what, the bed read $measured against $expected from the laid gain") {
      (abs(measured - expected) <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
    }
  }

  /**
   * The 880 Hz tone measured against the 440 Hz one at the same instant.
   *
   * Everything is read as a ratio of the two because an AAC round trip attenuates the whole mix,
   * and an absolute level would be pinning the codec rather than the gain.
   */
  private fun FloatArray.bedAgainstPrimary(at: Duration): Float = toneAt(at, BED_HZ) / toneAt(at, PRIMARY_HZ)

  /**
   * How far apart the lightest and darkest samples of a decoded frame sit.
   *
   * A fill is one flat color and reads near nothing here, where the fixture's own pattern spans
   * most of the range, so this says whether a frame carries a picture at all.
   */
  private fun ByteArray.spread(): Int {
    var low = Int.MAX_VALUE
    var high = Int.MIN_VALUE
    for (byte in this) {
      val value = byte.toInt() and 0xFF
      if (value < low) low = value
      if (value > high) high = value
    }
    return high - low
  }

  private companion object {
    // The middle of the timeline, which by then is a loop pass past the bed's own end.
    val MIXED_READING = 5_500.milliseconds

    // Inside the first pass, where the audio track's level is read before the loop repeats.
    val LOOP_VIDEO_REFERENCE = 500.milliseconds

    // How much of the fixture's own range a written frame has to span to count as carrying its
    // picture. A flat fill spans almost nothing, so half is generous and still nowhere near it.
    const val PICTURE_SPREAD_FLOOR = 0.5f
  }
}
