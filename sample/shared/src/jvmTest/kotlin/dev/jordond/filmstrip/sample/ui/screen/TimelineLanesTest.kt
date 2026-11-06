package dev.jordond.filmstrip.sample.ui.screen

import dev.jordond.filmstrip.sample.EditState
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class TimelineLanesTest {
  private val duration = 10.seconds

  private fun edit(block: EditState.() -> Unit = {}) = EditState().apply(block)

  private fun EditState.withText() {
    textEnabled = true
    text = "SUMMER"
  }

  private fun EditState.trim(
    start: Float,
    end: Float,
  ) {
    trimEnabled = true
    trimStartSeconds = start
    trimEndSeconds = end
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
  fun `a full trim reads both lanes across the whole source`() {
    val lanes =
      laneSpans(
        edit {
          trim(0f, 10f)
          brightness = 1.2f
          withText()
        },
        duration,
      )

    lanes.grade shouldBe LaneSpan(0.seconds, 10.seconds)
    lanes.text shouldBe LaneSpan(0.seconds, 10.seconds)
  }

  @Test
  fun `a text window mid edit reads shifted by the trim start`() {
    val lanes =
      laneSpans(
        edit {
          trim(4f, 9f)
          withText()
          textTimed = true
          textStartSeconds = 1f
          textEndSeconds = 3f
        },
        duration,
      )

    lanes.text shouldBe LaneSpan(5.seconds, 7.seconds)
  }

  @Test
  fun `a window past the kept end clamps to it`() {
    val lanes =
      laneSpans(
        edit {
          trim(2f, 6f)
          withText()
          textTimed = true
          textStartSeconds = 3f
          textEndSeconds = 8f
        },
        duration,
      )

    lanes.text shouldBe LaneSpan(5.seconds, 6.seconds)
  }

  @Test
  fun `a window wholly past the kept end leaves no lane`() {
    val lanes =
      laneSpans(
        edit {
          trim(2f, 6f)
          withText()
          textTimed = true
          textStartSeconds = 5f
          textEndSeconds = 8f
        },
        duration,
      )

    lanes.text.shouldBeNull()
  }

  @Test
  fun `a timed window with no length is the whole edit, as the composition reads it`() {
    val lanes =
      laneSpans(
        edit {
          trim(2f, 6f)
          withText()
          textTimed = true
          textStartSeconds = 3f
          textEndSeconds = 3f
        },
        duration,
      )

    lanes.text shouldBe LaneSpan(2.seconds, 6.seconds)
  }

  @Test
  fun `grade spans the kept range only`() {
    val lanes = laneSpans(edit { trim(3f, 7f); saturation = 0.5f }, duration)

    lanes.grade shouldBe LaneSpan(3.seconds, 7.seconds)
  }

  @Test
  fun `the VHS effect counts as a grade`() {
    laneSpans(edit { vhsEnabled = true }, duration).grade shouldBe LaneSpan(0.seconds, 10.seconds)
  }

  @Test
  fun `an untimed text lane spans the kept range`() {
    val lanes =
      laneSpans(
        edit {
          trim(1f, 4f)
          withText()
        },
        duration,
      )

    lanes.text shouldBe LaneSpan(1.seconds, 4.seconds)
  }
}
