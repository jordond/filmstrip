package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.FIRST_BLUE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.indexedClip
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a preview window draws for a clip at speed, read through a real ffmpeg.
 *
 * A window opened on a slot has to hand over the frame the graph reaches on that slot when read forward from its head,
 * which runs the graph the export writes. The frames are compared byte for byte, since both come through one sink.
 */
class FfmpegClipSpeedWindowMediaTest {
  private val frames = FilterFrames()

  @Test
  fun `a clip effect draws the same frame from any window at a speed in the middle of the range`() =
    runTest(timeout = TIMEOUT) {
      assertWindows(0.7, TimeRange.of(1.seconds, 2.seconds), ::clipEffects)
      assertWindows(1.5, TimeRange.of(1.seconds, 2.seconds), ::clipEffects)
    }

  @Test
  fun `a clip draws the same frame from any window on a trim opening off a frame`() =
    runTest(timeout = TIMEOUT) {
      assertWindows(0.7, OFF_FRAME_TRIM, ::clipEffects)
      assertWindows(0.7, OFF_FRAME_TRIM) { emptyList() }
    }

  @Test
  fun `a clip effect draws the same frame from any window at the slow end of the range`() =
    runTest(timeout = TIMEOUT) {
      assertWindows(CLIP_SPEED_RANGE.start, TimeRange.of(1.seconds, 1.seconds + 1.seconds / RATE), ::clipEffects)
    }

  @Test
  fun `a clip effect draws the same frame from any window at the fast end of the range`() =
    runTest(timeout = TIMEOUT) {
      assertWindows(CLIP_SPEED_RANGE.endInclusive, TimeRange.of(1.seconds, 9.seconds), ::clipEffects)
    }

  @Test
  fun `a bare clip draws the same frame from every window at the fast end of the range`() =
    runTest(timeout = TIMEOUT) {
      assertWindows(CLIP_SPEED_RANGE.endInclusive, TimeRange.of(1.seconds, 9.seconds)) { emptyList() }
    }

  private suspend fun assertWindows(
    speed: Double,
    trim: TimeRange,
    effects: (Duration) -> List<EffectSpec>,
  ) {
    val source = indexedClip(RATE, SOURCE_LENGTH, FIRST_BLUE)
    try {
      val length = outputLengthOf(checkNotNull(trim.duration), speed)
      val media = MediaSource.of(source.absolutePath)
      val drawn = effects(length)
      val composition = frames.composition(listOf(Clip(media, trim, effects = drawn, speed = speed)))
      val slots = outputSlotOf(length, RATE).toInt()
      val forward = frames.frames(composition, Duration.ZERO, slots)

      for (slot in 0 until slots) {
        val window = frames.frames(composition, outputSlotTime(slot.toLong(), RATE), 1).single()
        withClue("the window at slot $slot of $slots at $speed drew another frame than reading forward did") {
          window.contentEquals(forward[slot]).shouldBeTrue()
        }
      }

      if (drawn.isNotEmpty()) {
        val slot = slots / 2
        val bare = frames.composition(listOf(Clip(media, trim, speed = speed)))
        val unaffected = frames.frames(bare, outputSlotTime(slot.toLong(), RATE), 1).single()
        withClue("the clip's effects drew nothing in the window at slot $slot at $speed") {
          unaffected.contentEquals(forward[slot]).shouldBeFalse()
        }
      }
    } finally {
      source.delete()
    }
  }

  // A grade and a caption fading over the clip's whole length, so a window whose clock restarted draws the fade at its
  // opening value.
  private fun clipEffects(length: Duration): List<EffectSpec> =
    listOf(
      Brightness(GAIN),
      TextOverlay(text = CAPTION, anchor = Anchor.Center, animation = fade(inDuration = length)),
    )

  private companion object {
    val TIMEOUT = 5.minutes
    val SOURCE_LENGTH = 10.seconds
    val OFF_FRAME_TRIM = TimeRange.of(1_016.milliseconds, 2_016.milliseconds)
    const val RATE = 30
    const val GAIN = 0.2f
    const val CAPTION = "Speed"
  }
}
