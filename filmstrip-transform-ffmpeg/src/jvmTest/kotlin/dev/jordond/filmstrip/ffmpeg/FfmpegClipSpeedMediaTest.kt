package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.AUDIO_SAMPLE_RATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.FIRST_BLUE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.PRIMARY_HZ
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.assertOnTheGrid
import dev.jordond.filmstrip.ffmpeg.support.decodedAudio
import dev.jordond.filmstrip.ffmpeg.support.indexedClip
import dev.jordond.filmstrip.ffmpeg.support.toneAt
import dev.jordond.filmstrip.ffmpeg.support.writtenFrames
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * A clip at speed exported through a real ffmpeg: how many frames land on the grid, how long the audio runs and whether
 * its tone stayed where the source had it.
 */
class FfmpegClipSpeedMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a clip at a speed in the middle of the range holds its length and its pitch`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertSpeed(0.7, sourceLength = 2.seconds)
      assertSpeed(1.5, sourceLength = 3.seconds)
    }

  @Test
  fun `a clip at the slow end of the range holds its length and its pitch`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertSpeed(CLIP_SPEED_RANGE.start, sourceLength = 500.milliseconds)
    }

  @Test
  fun `a clip at the fast end of the range holds its length and its pitch`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertSpeed(CLIP_SPEED_RANGE.endInclusive, sourceLength = 16.seconds)
    }

  // Reads the frame the fixture stamped its own index into, so a frame taken from the wrong place in the source shows
  // up
  // here while its length stays right.
  @Test
  fun `each output slot shows the source frame the shared offset reaches`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      listOf(0.7, 1.5).forEach { speed ->
        val source = indexedClip(RATE, 3.seconds, FIRST_BLUE)
        val output = File.createTempFile("filmstrip-speed-frames", ".mp4").also { it.delete() }
        val trimStart = 1.seconds
        val trimmed = 1.seconds
        val composition =
          compositionOf {
            clip(MediaSource.of(source.absolutePath)) {
              trim(TimeRange.of(trimStart, trimStart + trimmed))
              speed(speed)
            }
          }
        fixture.exported(fixture.plannedRun(composition, ExportSpec(frameRate = RATE)), output)

        val frames = writtenFrames(output.absolutePath)
        val slot = frames.size / 2
        val shown = frames[slot].shown.shouldNotBeNull().index
        val reached = trimStart + sourceOffsetAt(outputSlotTime(slot.toLong(), RATE), speed)
        val expected = reached.toDouble(DurationUnit.SECONDS) * RATE
        withClue("slot $slot at speed $speed showed source frame $shown, expected near $expected") {
          (abs(shown - expected) <= 1.5).shouldBeTrue()
        }

        source.delete()
        output.delete()
      }
    }

  private suspend fun assertSpeed(
    speed: Double,
    sourceLength: Duration,
  ) {
    val source = indexedClip(RATE, sourceLength, FIRST_BLUE, sound = sourceLength)
    val output = File.createTempFile("filmstrip-speed", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        clip(MediaSource.of(source.absolutePath)) {
          trim(TimeRange.of(Duration.ZERO, sourceLength))
          speed(speed)
        }
      }
    val length = outputLengthOf(sourceLength, speed)
    composition.duration shouldBe length

    fixture.exported(fixture.plannedRun(composition, ExportSpec(frameRate = RATE)), output)

    assertOnTheGrid(
      writtenFrames(output.absolutePath),
      outputSlotOf(length, RATE),
      RATE,
      "a clip of $sourceLength at speed $speed",
    )
    val samples = decodedAudio(output.absolutePath)
    val written = samples.size.toDouble() / AUDIO_SAMPLE_RATE
    val expected = length.toDouble(DurationUnit.SECONDS)
    withClue("speed $speed wrote $written s of audio, expected $expected s") {
      (abs(written - expected) <= AUDIO_LENGTH_TOLERANCE_SECONDS).shouldBeTrue()
    }

    // Read at the middle of the clip. The tone the source carries is still there, and the one a resampler would have
    // shifted it to is not.
    val middle = length / 2
    val tone = samples.toneAt(middle, PRIMARY_HZ)
    val shifted = samples.toneAt(middle, shiftedHz(speed))
    withClue("speed $speed read $tone at $PRIMARY_HZ Hz and $shifted at the shifted pitch") {
      (tone > MIN_TONE).shouldBeTrue()
      (shifted < tone / 2).shouldBeTrue()
    }

    source.delete()
    output.delete()
  }

  // Where a resampler would have moved the fixture's tone, held inside what a reading window can resolve.
  private fun shiftedHz(speed: Double): Int = (PRIMARY_HZ * speed).toInt().coerceIn(MIN_SHIFTED_HZ, MAX_SHIFTED_HZ)

  private companion object {
    const val RATE = 30

    // One AAC frame of priming and one of padding either way, which the encoder adds on its own.
    const val AUDIO_LENGTH_TOLERANCE_SECONDS = 2.0 * 1024 / AUDIO_SAMPLE_RATE

    const val MIN_TONE = 0.05f
    const val MIN_SHIFTED_HZ = 60
    const val MAX_SHIFTED_HZ = 8_000
  }
}
