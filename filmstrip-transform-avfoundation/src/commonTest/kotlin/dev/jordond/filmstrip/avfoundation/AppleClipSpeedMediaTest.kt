package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.AudioStretchFailure
import dev.jordond.filmstrip.avfoundation.internal.StretchedAudio
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.avfoundation.internal.renderClipAudio
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetTrackSegment
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.naturalTimeScale
import platform.AVFoundation.segments
import platform.AVFoundation.timeRange
import platform.AVFoundation.tracksWithMediaType
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * A clip at speed exported through AVFoundation: how many frames land on the grid, how long the audio runs and whether
 * its tone stayed where the source had it.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleClipSpeedMediaTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a clip at a speed in the middle of the range holds its length and its pitch`() =
    runTest(timeout = TIMEOUT) {
      assertSpeed(0.7, fixture(SHORT), 2.seconds, "seventy")
      assertSpeed(1.5, fixture(SHORT), 2.seconds, "one-fifty")
    }

  @Test
  fun `a clip at the slow end of the range holds its length and its pitch`() =
    runTest(timeout = TIMEOUT) {
      assertSpeed(CLIP_SPEED_RANGE.start, fixture(SHORT), 500.milliseconds, "slowest")
    }

  @Test
  fun `a clip at the fast end of the range holds its length and its pitch`() =
    runTest(timeout = TIMEOUT) {
      assertSpeed(CLIP_SPEED_RANGE.endInclusive, fixture(LONG), 12.seconds, "fastest", MIN_FAST_TONE)
    }

  // The speeds a scaled composition's offline mix hangs at or comes back short from.
  @Test
  fun `a clip at the speeds the offline mix cannot scale holds its length and its pitch`() =
    runTest(timeout = TIMEOUT) {
      listOf(14.0, 16.0, 20.0).forEach { speed ->
        assertSpeed(speed, fixture(LONG), 12.seconds, "fast-$speed", MIN_FAST_TONE)
      }
    }

  // Reads the frame the fixture stamped its own index into, so a frame taken from the wrong place in the source shows
  // up
  // here while its length stays right.
  @Test
  fun `each output slot shows the source frame the shared offset reaches`() =
    runTest(timeout = TIMEOUT) {
      val written = NSTemporaryDirectory() + "filmstrip-apple-speed-indexed.mov"
      withContext(Dispatchers.Default) { writeOffGridClip(written, RATE, FRAMES, 1.seconds * FRAMES / RATE) }

      listOf(0.7, 1.5).forEach { speed ->
        val composition = compositionOf { clip(MediaSource.of(written)) { speed(speed) } }
        val output = NSTemporaryDirectory() + "filmstrip-apple-speed-frames-$speed.mp4"
        exported(composition, output)

        val slot = (outputSlotOf(outputLengthOf(1.seconds * FRAMES / RATE, speed), RATE) / 2)
        val time = outputSlotTime(slot, RATE)
        val shown = frameIndexOf(frameOf(output, time))
        val expected = sourceOffsetAt(time, speed).toDouble(DurationUnit.SECONDS) * RATE
        withClue("slot $slot at speed $speed showed source frame $shown, expected near $expected") {
          (abs(shown - expected) <= FRAME_TOLERANCE).shouldBeTrue()
        }
        remove(output)
      }
      remove(written)
    }

  // A looping pass at a speed ends off the grid and on a picture shorter than its sound, so a slot holds the clip's
  // last frame there. The frame count and every sample time come from the planned length and the grid.
  @Test
  fun `a looping clip at a speed with a held tail writes the frames of its shared length`() =
    runTest(timeout = TIMEOUT) {
      val offGrid = NSTemporaryDirectory() + "filmstrip-apple-speed-loop-clip.mov"
      withContext(Dispatchers.Default) { writeOffGridClip(offGrid, LOOP_RATE, LOOP_FRAMES, LOOP_END) }
      val composition =
        compositionOf {
          track {
            clip(MediaSource.of(offGrid)) { speed(LOOP_SPEED) }
            looping()
          }
          track(TrackContent.Audio) {
            clip(MediaSource.of(fixture(LONG))) { trim(TimeRange.of(Duration.ZERO, LOOP_RUN)) }
          }
        }
      val pass = outputLengthOf(LOOP_END, LOOP_SPEED)
      val laid =
        resolved(composition, LOOP_RATE)
          .tracks
          .single { it.content != TrackContent.Audio }
          .clips
      laid.first().span.duration shouldBe pass
      val output = NSTemporaryDirectory() + "filmstrip-apple-speed-loop.mp4"

      exported(composition, output, LOOP_RATE)

      val times = sampleTimesOf(output)
      withClue("a looping clip at $LOOP_SPEED wrote ${times.size} frames: $times") {
        times.size.toLong() shouldBe outputSlotOf(LOOP_RUN, LOOP_RATE)
        times.forEachIndexed { index, time ->
          ((time - outputSlotTime(index.toLong(), LOOP_RATE)).absoluteValue <= SAMPLE_TOLERANCE).shouldBeTrue()
        }
      }

      val first = outputSlotsOf(laid[0].span, LOOP_RATE)
      val held = outputSlotTime(first.last, LOOP_RATE)
      val reopened = outputSlotTime(outputSlotsOf(laid[1].span, LOOP_RATE).first, LOOP_RATE)
      frameIndexOf(frameOf(output, held)) shouldBe LOOP_FRAMES - 1
      frameIndexOf(frameOf(output, reopened)) shouldBe 0

      remove(output)
      remove(offGrid)
    }

  // A picture a few source ticks short of the slot its length rounds up to leaves a held tail, and at this speed the
  // frames ahead of the last one round up to the whole length unless a tick is kept back for that last one.
  @Test
  fun `a clip at the fast end of the range with a held tail writes the frames of its shared length`() =
    runTest(timeout = TIMEOUT) {
      val offGrid = NSTemporaryDirectory() + "filmstrip-apple-speed-fast-held-clip.mov"
      withContext(Dispatchers.Default) {
        writeOffGridClip(offGrid, FAST_SOURCE_RATE, LOOP_FRAMES, FAST_PICTURE)
      }
      val speed = CLIP_SPEED_RANGE.endInclusive
      val composition = compositionOf { clip(MediaSource.of(offGrid)) { speed(speed) } }
      val length = outputLengthOf(FAST_PICTURE, speed)
      val lowered = withContext(Dispatchers.Default) { resolved(composition, LOOP_RATE).toAvComposition() }
      val lastSegment =
        (lowered.composition.tracksWithMediaType(AVMediaTypeVideo).first() as AVAssetTrack)
          .segments
          .last() as AVAssetTrackSegment
      val slots = outputSlotOf(length, LOOP_RATE)
      val laidTicks = (outputSlotTime(slots, LOOP_RATE).toDouble(DurationUnit.SECONDS) * MEDIA_TIMESCALE).roundToLong()
      lastSegment.timeMapping.useContents {
        withClue("the last frame read ${source.duration.value} source ticks onto ${target.duration.value}") {
          source.duration.value shouldBe 1L
          target.start.value + target.duration.value shouldBe laidTicks
        }
      }
      val output = NSTemporaryDirectory() + "filmstrip-apple-speed-fast-held.mp4"

      exported(composition, output, LOOP_RATE)

      val times = sampleTimesOf(output)
      withClue("a clip at $speed wrote ${times.size} frames: $times") {
        times.size.toLong() shouldBe outputSlotOf(length, LOOP_RATE)
        times.forEachIndexed { index, time ->
          ((time - outputSlotTime(index.toLong(), LOOP_RATE)).absoluteValue <= SAMPLE_TOLERANCE).shouldBeTrue()
        }
      }
      val last = outputSlotTime(times.size.toLong() - 1, LOOP_RATE)
      val reached = sourceOffsetAt(last, speed).toDouble(DurationUnit.SECONDS) * FAST_SOURCE_RATE
      withClue("the last slot should reach source frame $reached") {
        (abs(frameIndexOf(frameOf(output, last)) - reached) <= FRAME_TOLERANCE).shouldBeTrue()
      }

      remove(output)
      remove(offGrid)
    }

  @Test
  fun `two clips at different speeds keep their own tone and a fade across them reads in output time`() =
    runTest(timeout = TIMEOUT) {
      val first = 1.seconds
      val second = 2.seconds
      val firstLength = outputLengthOf(first, FIRST_SPEED)
      val length = firstLength + outputLengthOf(second, SECOND_SPEED)
      val composition =
        compositionOf {
          track(TrackContent.AudioAndVideo) {
            clip(MediaSource.of(fixture(SHORT))) {
              trim(TimeRange.of(Duration.ZERO, first))
              speed(FIRST_SPEED)
            }
            clip(MediaSource.of(fixture(BED))) {
              trim(TimeRange.of(Duration.ZERO, second))
              speed(SECOND_SPEED)
            }
            fadeIn(length)
          }
        }
      composition.duration shouldBe length
      val output = NSTemporaryDirectory() + "filmstrip-apple-speed-two-clips.mp4"

      exported(composition, output)

      val audio = audioOf(output)
      val written = audio.samples.size.toDouble() / audio.sampleRate
      withClue("wrote $written s of audio against $length") {
        (abs(written - length.toDouble(DurationUnit.SECONDS)) <= AUDIO_LENGTH_TOLERANCE_SECONDS).shouldBeTrue()
      }

      val firstAt = firstLength / 2
      val secondAt = firstLength + (length - firstLength) / 2
      val firstTone = audio.toneOver(firstAt - TONE_WINDOW / 2, TONE_WINDOW, PRIMARY_HZ)
      val secondTone = audio.toneOver(secondAt - TONE_WINDOW / 2, TONE_WINDOW, BED_HZ)
      withClue("the first clip read $firstTone and the second read $secondTone") {
        (audio.toneOver(firstAt - TONE_WINDOW / 2, TONE_WINDOW, BED_HZ) < firstTone / 2).shouldBeTrue()
        (audio.toneOver(secondAt - TONE_WINDOW / 2, TONE_WINDOW, PRIMARY_HZ) < secondTone / 2).shouldBeTrue()
      }

      // The fade is linear over the whole output, so one source tone at two points reads in the ratio of their gains.
      val early = firstLength / 4
      val late = firstLength * 3 / 4
      val earlyTone = audio.toneOver(early - TONE_WINDOW / 2, TONE_WINDOW, PRIMARY_HZ)
      val lateTone = audio.toneOver(late - TONE_WINDOW / 2, TONE_WINDOW, PRIMARY_HZ)
      withClue("the fade read $earlyTone at $early and $lateTone at $late") {
        val ratio = earlyTone / lateTone
        (abs(ratio - early / late) <= FADE_RATIO_TOLERANCE).shouldBeTrue()
      }
      remove(output)
    }

  @Test
  fun `an export reports its start once whether or not it renders first`() =
    runTest(timeout = TIMEOUT) {
      listOf(1.0, 1.5).forEach { speed ->
        val statuses =
          withContext(Dispatchers.Default) {
            val composition = compositionOf { clip(MediaSource.of(fixture(SHORT))) { speed(speed) } }
            val sink = MediaSink.of(NSTemporaryDirectory() + "filmstrip-apple-speed-started-$speed.mp4")
            filmstrip.export(composition, ExportSpec(frameRate = RATE), sink).toList()
          }
        withClue("at speed $speed") {
          statuses.count { it == ExportStatus.Started } shouldBe 1
          statuses.indexOfFirst { it == ExportStatus.Started } shouldBe
            statuses.indexOfFirst { it !is ExportStatus.Adjusted }
        }
      }
    }

  private suspend fun resolved(
    composition: Composition,
    rate: Int = RATE,
  ): ResolvedComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, ExportSpec(frameRate = rate))) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    }

  private suspend fun assertSpeed(
    speed: Double,
    source: String,
    sourceLength: Duration,
    name: String,
    minTone: Float = MIN_TONE,
  ) {
    val composition =
      compositionOf {
        clip(MediaSource.of(source)) {
          trim(TimeRange.of(Duration.ZERO, sourceLength))
          speed(speed)
        }
      }
    val length = outputLengthOf(sourceLength, speed)
    composition.duration shouldBe length
    val output = NSTemporaryDirectory() + "filmstrip-apple-speed-$name.mp4"

    exported(composition, output)

    val times = sampleTimesOf(output)
    withClue("speed $speed wrote ${times.size} frames: $times") {
      times.size.toLong() shouldBe outputSlotOf(length, RATE)
      times.forEachIndexed { index, time ->
        ((time - outputSlotTime(index.toLong(), RATE)).absoluteValue <= SAMPLE_TOLERANCE).shouldBeTrue()
      }
    }

    val audio = audioOf(output)
    val written = audio.samples.size.toDouble() / audio.sampleRate
    val expected = length.toDouble(DurationUnit.SECONDS)
    withClue("speed $speed wrote $written s of audio, expected $expected s") {
      (abs(written - expected) <= AUDIO_LENGTH_TOLERANCE_SECONDS).shouldBeTrue()
    }

    // Read at the middle of the clip. The tone the source carries is still there, and the one a resampler would have
    // shifted it to is not.
    val opens = length / 2 - TONE_WINDOW / 2
    val tone = audio.toneOver(opens, TONE_WINDOW, PRIMARY_HZ)
    val shifted = audio.toneOver(opens, TONE_WINDOW, shiftedHz(speed))
    withClue("speed $speed read $tone at $PRIMARY_HZ Hz and $shifted at the shifted pitch") {
      (tone > minTone).shouldBeTrue()
      (shifted < tone / 2).shouldBeTrue()
    }

    remove(output)
  }

  // Where a resampler would have moved the fixture's tone, held inside what a reading window can resolve.
  private fun shiftedHz(speed: Double): Int = (PRIMARY_HZ * speed).toInt().coerceIn(MIN_SHIFTED_HZ, MAX_SHIFTED_HZ)

  private suspend fun exported(
    composition: Composition,
    output: String,
    rate: Int = RATE,
  ) = withContext(Dispatchers.Default) {
    val statuses = filmstrip.export(composition, ExportSpec(frameRate = rate), MediaSink.of(output)).toList()
    when (val finished = statuses.last()) {
      is ExportStatus.Failure -> error(finished.error.message)
      else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
    }
  }

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    val TIMEOUT = 3.minutes
    const val SHORT = "apple_export_a.mp4"
    const val LONG = "apple_export_long.mp4"
    const val BED = "tone_bed_880.mp4"
    const val FIRST_SPEED = 0.7
    const val SECOND_SPEED = 1.5
    const val BED_HZ = 880
    const val FADE_RATIO_TOLERANCE = 0.08
    const val RATE = 30
    const val LOOP_RATE = 25
    const val LOOP_FRAMES = 26
    const val FAST_SOURCE_RATE = 3
    val FAST_PICTURE = 8_958.milliseconds
    const val LOOP_SPEED = 0.7
    val LOOP_END = 1_030.milliseconds
    val LOOP_RUN = 3_090.milliseconds
    const val FRAMES = 27
    const val FRAME_TOLERANCE = 1.5
    const val PRIMARY_HZ = 440
    const val MIN_TONE = 0.05f

    // AVAudioUnitTimePitch spreads a lone tone across its neighbours at these rates. The file's level holds, within a
    // tenth of a slow clip's, while a reading at one frequency falls to as little as 0.032 of 0.088 at 14x.
    const val MIN_FAST_TONE = 0.02f
    const val MIN_SHIFTED_HZ = 60
    const val MAX_SHIFTED_HZ = 8_000
    const val AUDIO_LENGTH_TOLERANCE_SECONDS = 2.0 * 1024 / 48_000
    val SAMPLE_TOLERANCE = 1.milliseconds
    val TONE_WINDOW = 100.milliseconds
  }
}
