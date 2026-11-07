package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.sourceLengthOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.FakeResolver
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the timeline phase sizes the composition from the tracks that bound it, lays every track over that, and the
 * refusals it reaches before anything is lowered.
 */
class TimelinePhaseTest {
  private val fixture = PlannerFixture()

  @Test
  fun `the composition runs as long as its longest bounded track`() {
    val primary = Track(listOf(fixture.clip(duration = 2.seconds), fixture.clip(duration = 1_500.milliseconds)))
    val bed =
      Track(listOf(fixture.audioOnlyClip(duration = 2.seconds)), content = TrackContent.Audio, start = 2.seconds)

    val timeline = next(EditComposition(listOf(primary, bed)))

    timeline.duration shouldBe 4.seconds
    timeline.span shouldBe TimeRange.of(Duration.ZERO, 4.seconds)
    timeline.tracks.map { it.duration } shouldBe listOf(3_500.milliseconds, 4.seconds)
  }

  // A trim is the window the composition is sized from, not the source's own length.
  @Test
  fun `a trimmed clip bounds the composition by its window`() {
    val clip = fixture.clip(duration = 6.seconds, trim = TimeRange.of(1.seconds, 3_500.milliseconds))

    next(EditComposition(listOf(Track(listOf(clip))))).duration shouldBe 2_500.milliseconds
  }

  // The looping bed is laid over the run the bounded track sets, as many passes as that takes, and
  // never sets the duration itself.
  @Test
  fun `a looping track fills the run its bounded neighbour sets`() {
    val primary = Track(listOf(fixture.clip(duration = 5.seconds)))
    val bed = Track(listOf(fixture.audioOnlyClip(duration = 2.seconds)), content = TrackContent.Audio, looping = true)

    val timeline = next(EditComposition(listOf(primary, bed)))

    timeline.duration shouldBe 5.seconds
    val laid = timeline.tracks[1]
    laid.clips.size shouldBe 3
    laid.duration shouldBe 5.seconds
  }

  @Test
  fun `a clip that trims to nothing is refused by name`() {
    val emptied = fixture.clip(duration = 2.seconds, trim = TimeRange.of(3.seconds, 4.seconds))

    refusal(EditComposition(listOf(Track(listOf(emptied))))) shouldContain "trims to nothing"
  }

  @Test
  fun `a negative composition volume is refused`() {
    val edit = EditComposition(listOf(Track(listOf(fixture.clip()))), audio = AudioSpec.Volume(-0.5f))

    refusal(edit) shouldContain "negative or non-finite gain"
  }

  // A looping primary takes its run from the bed, and opening past the bed's end leaves it none.
  @Test
  fun `a primary that opens after the composition ends is refused`() {
    val late = Track(listOf(fixture.clip(duration = 1.seconds)), start = 6.seconds, looping = true)
    val bed = Track(listOf(fixture.audioOnlyClip(duration = 5.seconds)), content = TrackContent.Audio)

    refusal(EditComposition(listOf(late, bed))) shouldContain "starts at or after the composition ends"
  }

  // Both ends of a 10 ms primary land on the first slot of a 30 fps grid, so it starts and ends on
  // the same frame.
  @Test
  fun `a primary whose video covers no output frame is refused`() {
    val sliver = Track(listOf(fixture.clip(duration = 10.milliseconds)))

    refusal(EditComposition(listOf(sliver))) shouldContain "covers none"
  }

  @Test
  fun `an audio only export writes no picture for the primary to cover`() {
    val sliver = Track(listOf(fixture.clip(duration = 10.milliseconds, audioRate = 48_000)))

    next(EditComposition(listOf(sliver)), keepVideo = false).duration shouldBe 10.milliseconds
  }

  @Test
  fun `laid windows read a trimmed clip as its trim`() {
    val clip = fixture.clip(duration = 6.seconds, trim = TimeRange.of(1.seconds, 3_500.milliseconds))
    val edit = EditComposition(listOf(Track(listOf(clip))))

    val windows = laidWindows(edit, ask(edit)) { fail("the clip keeps frames") }

    val window = windows.tracks.single().single()
    window.from shouldBe 1.seconds
    window.to shouldBe 3_500.milliseconds
    window.length shouldBe 2_500.milliseconds
    windows.duration shouldBe edit.duration
  }

  @Test
  fun `laid windows hold a still for its span`() {
    val still = fixture.imageClip(duration = 5.seconds, trim = TimeRange.of(1.seconds, 3.seconds))
    val edit = EditComposition(listOf(Track(listOf(still))))

    val windows = laidWindows(edit, ask(edit)) { fail("the still holds for a while") }

    val window = windows.tracks.single().single()
    window.from shouldBe Duration.ZERO
    window.to shouldBe 2.seconds
    window.length shouldBe 2.seconds
    windows.duration shouldBe 2.seconds
  }

  @Test
  fun `laid windows leave a looping track out of the composition duration`() {
    val primary = Track(listOf(fixture.clip(duration = 2.seconds)), start = 1.seconds)
    val bed = Track(listOf(fixture.audioOnlyClip(duration = 9.seconds)), content = TrackContent.Audio, looping = true)
    val edit = EditComposition(listOf(primary, bed))

    val windows = laidWindows(edit, ask(edit)) { fail("every clip keeps frames") }

    windows.tracks.map { track -> track.map { it.length } } shouldBe listOf(listOf(2.seconds), listOf(9.seconds))
    windows.duration shouldBe 3.seconds
    next(edit).duration shouldBe windows.duration
  }

  @Test
  fun `laid windows name the clip that trims to nothing`() {
    val emptied = fixture.clip(duration = 2.seconds, trim = TimeRange.of(3.seconds, 4.seconds))
    val edit = EditComposition(listOf(Track(listOf(emptied))))

    clipTrimmedToNothing(edit) shouldBe emptied
  }

  @Test
  fun `the timeline and the public model read the same length at speed`() {
    listOf(0.7, 1.5).forEach { speed ->
      val clip = fixture.clip(duration = 6.seconds, trim = TimeRange.of(1.seconds, 4.seconds)).withSpeed(speed)
      val edit = EditComposition(listOf(Track(listOf(clip))))

      val timeline = next(edit)

      timeline.duration shouldBe outputLengthOf(3.seconds, speed)
      timeline.duration shouldBe edit.duration
      timeline.tracks.single().duration shouldBe outputLengthOf(3.seconds, speed)
    }
  }

  @Test
  fun `a looping track under a clip at speed fills the output run`() {
    val speed = 0.7
    val primary = Track(listOf(fixture.clip(duration = 3.seconds).withSpeed(speed)))
    val bed =
      Track(
        listOf(fixture.audioOnlyClip(duration = 2.seconds).withSpeed(speed)),
        content = TrackContent.Audio,
        looping = true,
      )

    val timeline = next(EditComposition(listOf(primary, bed)))

    timeline.duration shouldBe outputLengthOf(3.seconds, speed)
    timeline.tracks[1].clips.fold(Duration.ZERO) { total, clip -> total + clip.outputDuration } shouldBe
      timeline.duration
    timeline.tracks[1].clips.last().let { last ->
      last.end shouldBe last.start + sourceLengthOf(last.outputDuration, speed)
    }
  }

  private fun clipTrimmedToNothing(edit: EditComposition): Clip {
    laidWindows(edit, ask(edit)) { return it }
    fail("every clip keeps frames")
  }

  private fun next(
    edit: EditComposition,
    keepVideo: Boolean = true,
  ): Timeline = timeline(edit, keepVideo).shouldBeInstanceOf<Phase.Next<Timeline>>().value

  private fun refusal(edit: EditComposition): String {
    val error = timeline(edit, keepVideo = true).shouldBeInstanceOf<Phase.Refused>().error
    return error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message
  }

  private fun timeline(
    edit: EditComposition,
    keepVideo: Boolean,
  ): Phase<Timeline> =
    timelineOf(
      edit = edit,
      ask = ask(edit),
      grade = gradeOf(),
      encoding = encodingOf(OUTPUT),
      streams = Streams(keepVideo = keepVideo, keepAudio = true),
      lowering = StageLowering(listOf(FakeResolver()), { "nothing claimed $it" }),
      naturalSize = OUTPUT,
    )

  private fun ask(edit: EditComposition): Ask =
    Ask(
      spec = ExportSpec(),
      device = fixture.device(),
      infos = fixture.infos(edit),
      openings = emptyMap(),
      layoutSize = null,
    )

  private companion object {
    val OUTPUT = Size(1920, 1080)
  }
}
