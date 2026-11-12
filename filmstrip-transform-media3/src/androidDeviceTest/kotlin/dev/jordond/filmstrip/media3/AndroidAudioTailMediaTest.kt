package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.audioFramesOf
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeInRange
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The end of the audio in a media3 export, read off the file.
 *
 * The fixture carries a steady tone, so a clip cut inside it is loud up to its last frame. The file's audio has to run
 * to the end of the plan and still carry the tone there, on an encoder that flushes its stream and on one that needs
 * the export's pad to.
 */
class AndroidAudioTailMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aClipEndingOnAWholeAccessUnitKeepsItsLastFrames() =
    runTest(timeout = TIMEOUT) {
      assertTailKept(ON_A_UNIT)
    }

  @Test
  fun aClipEndingHalfwayIntoAnAccessUnitKeepsItsLastFrames() =
    runTest(timeout = TIMEOUT) {
      assertTailKept(MID_UNIT)
    }

  @Test
  fun aClipEndingNearTheEndOfAnAccessUnitKeepsItsLastFrames() =
    runTest(timeout = TIMEOUT) {
      assertTailKept(LATE_IN_A_UNIT)
    }

  private suspend fun assertTailKept(length: Duration) {
    val clip = Clip(probe.fixture(), TimeRange.of(Duration.ZERO, length))
    val plan = probe.plan(Composition(listOf(Track(listOf(clip)))))
    val file = probe.export(plan)
    val audio = decodeAudio(file)
    file.delete()

    val planned = audioFramesOf(plan.duration, audio.sampleRate)
    val written = audio.samples.size.toLong()
    withClue("wrote $written frames of audio for a plan of $planned, ${written - planned} over") {
      written shouldBeInRange planned..planned + AAC_TAIL_FRAMES
    }
    val middle = audio.amplitudeOver(plan.duration / 2, TONE_WINDOW, TONE_HZ)
    val end = audio.amplitudeOver(plan.duration - TONE_WINDOW, TONE_WINDOW, TONE_HZ)
    withClue("the last $TONE_WINDOW of the plan carries $end at $TONE_HZ Hz against $middle in the middle") {
      end shouldBeGreaterThanOrEqual middle * TONE_KEPT
    }
  }

  private companion object {
    val TIMEOUT = 3.minutes

    // 30 whole access units of 1024 frames at the fixture's 48 kHz, then 480 and 896 frames into the next one.
    val ON_A_UNIT = 640.milliseconds
    val MID_UNIT = 650.milliseconds
    val LATE_IN_A_UNIT = 1_000.milliseconds

    // The AAC encoder's limit: it rounds the stream up to a whole access unit and flushes some of another.
    const val AAC_TAIL_FRAMES = 1_536L
    val TONE_WINDOW = 20.milliseconds
    const val TONE_HZ = 440

    // A dropped tail reads as nothing, and the encoder's own ringing at the end of a stream stays well above half.
    const val TONE_KEPT = 0.5f
  }
}
