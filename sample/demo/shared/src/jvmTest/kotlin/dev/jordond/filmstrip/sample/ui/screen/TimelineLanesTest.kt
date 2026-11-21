package dev.jordond.filmstrip.sample.ui.screen

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.sample.ClipContent
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.Sequence
import dev.jordond.filmstrip.sample.addProbed
import dev.jordond.filmstrip.sample.composition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TimelineLanesTest {
  private val sequence = Sequence()

  private val first = sequence.addProbed(MediaSource.of("/tmp/first.mp4"), 5.seconds)

  private val fast =
    sequence.addProbed(MediaSource.of("/tmp/second.mp4"), 6.seconds).apply {
      trimEnabled = true
      trimStartSeconds = 1f
      trimEndSeconds = 5f
      speed = 2.0
    }

  private val duration = sequence.duration!!

  private fun edit(block: EditState.() -> Unit = {}) = EditState().apply(block)

  private fun EditState.withText(
    start: Float? = null,
    end: Float? = null,
  ) {
    textEnabled = true
    text = "SUMMER"
    if (start != null && end != null) {
      textTimed = true
      textStartSeconds = start
      textEndSeconds = end
    }
  }

  @Test
  fun `an untouched edit has no lanes`() {
    val lanes = laneSpans(edit(), duration)

    lanes.grade.shouldBeNull()
    lanes.text.shouldBeNull()
  }

  @Test
  fun `blank text does not contribute`() {
    laneSpans(edit { textEnabled = true }, duration).text.shouldBeNull()
  }

  @Test
  fun `a grade and untimed text run the whole edit`() {
    val lanes =
      laneSpans(
        edit {
          grade.brightness = 1.2f
          withText()
        },
        duration,
      )

    lanes.grade shouldBe LaneSpan(Duration.ZERO, duration)
    lanes.text shouldBe LaneSpan(Duration.ZERO, duration)
  }

  @Test
  fun `the tape look counts as a grade`() {
    laneSpans(edit { tapeEnabled = true }, duration).grade shouldBe LaneSpan(Duration.ZERO, duration)
  }

  @Test
  fun `a text window over two clips spans the boundary between them`() {
    val text = laneSpans(edit { withText(3f, 6.5f) }, duration).text!!
    val boundary = sequence.clipStarts()[1]

    text shouldBe LaneSpan(3.seconds, 6500.milliseconds)
    text.start shouldBeLessThan boundary
    text.end shouldBeGreaterThan boundary
  }

  @Test
  fun `a window past the end of the edit clamps to it`() {
    laneSpans(edit { withText(6f, 20f) }, duration).text shouldBe LaneSpan(6.seconds, duration)
  }

  @Test
  fun `a window wholly past the end leaves no lane`() {
    laneSpans(edit { withText(8f, 9f) }, duration).text.shouldBeNull()
  }

  @Test
  fun `a timed window with no length is the whole edit, as the composition reads it`() {
    laneSpans(edit { withText(3f, 3f) }, duration).text shouldBe LaneSpan(Duration.ZERO, duration)
  }

  @Test
  fun `a lane on a clip's own strip covers the source it plays over`() {
    val text = laneSpans(edit { withText(3f, 6.5f) }, duration).text!!

    text.onClip(sequence, first) shouldBe LaneSpan(3.seconds, 5.seconds)
    text.onClip(sequence, fast) shouldBe LaneSpan(1.seconds, 4.seconds)
  }

  @Test
  fun `a grade on a trimmed clip's strip covers what the trim keeps`() {
    val grade = laneSpans(edit { grade.saturation = 0.5f }, duration).grade!!

    grade.onClip(sequence, fast) shouldBe LaneSpan(1.seconds, 5.seconds)
  }

  @Test
  fun `a clip with a grade of its own has a lane over its run alone`() {
    clipGradeSpans(sequence).shouldBeEmpty()

    fast.grade.saturation = 0.5f

    clipGradeSpans(sequence) shouldContainExactly listOf(LaneSpan(first.duration!!, duration))
    fast.grade.reset()
  }

  @Test
  fun `a lane that ends before a clip starts is not on its strip`() {
    val lanes = laneSpans(edit { withText(1f, 4f) }, duration).onClip(sequence, fast)

    lanes.text.shouldBeNull()
    lanes.grade.shouldBeNull()
  }

  @Test
  fun `the clip marks start where the sequence says each clip starts`() {
    val marks = clipSpans(sequence)

    marks.map { it.start } shouldContainExactly sequence.clipStarts()
    marks.map { it.id } shouldContainExactly listOf(first.id, fast.id)
    marks[1] shouldBe ClipSpan(fast.id, first.duration!!, duration)
  }

  @Test
  fun `a track lane starts at the track's start and a looping one runs to the end of the edit`() {
    val timed = sequence.addTrack("voice").apply { startSeconds = 1.5f }
    sequence.addTo(timed, ClipContent.Video(MediaSource.of("/tmp/voice.m4a")), "voice").probed(heard(2.seconds))
    val looped =
      sequence.addTrack("music").apply {
        startSeconds = 2.5f
        looping = true
      }
    sequence.addTo(looped, ClipContent.Video(MediaSource.of("/tmp/music.m4a")), "music").probed(heard(1.seconds))
    sequence.addTrack("empty")

    val built = EditState().composition(sequence).tracks
    trackSpans(sequence, duration) shouldContainExactly
      listOf(
        TrackSpan("voice", LaneSpan(built[1].start, built[1].start + 2.seconds)),
        TrackSpan("music", LaneSpan(built[2].start, duration)),
      )
    trackSpans(sequence, duration)[0].span shouldBe LaneSpan(1500.milliseconds, 3500.milliseconds)
  }

  @OptIn(InternalFilmstripApi::class)
  private fun heard(duration: Duration): ProbeResult = ProbeResult.Success(MediaInfo(duration, null, emptyList(), true))
}
