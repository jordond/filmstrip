package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.audio.StretchedAudio
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserAudioPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * How the live audio graph anchors its clock and walks the timeline a window at a time.
 *
 * Each scheduling pass reads the tracks once, so counting the reads counts the windows. Whether the
 * browser lets the context run depends on its autoplay policy, so nothing here waits on sound.
 */
class BrowserAudioPreviewMediaTest {
  @Test
  fun aPumpBeforeStartSchedulesNothing() =
    runTest {
      val reads = TrackReads()
      val preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.pump(Duration.ZERO)

        reads.count shouldBe 0
      } finally {
        preview.release()
      }
    }

  // Half a second of look-ahead in quarter-second windows is two passes from a standing start.
  @Test
  fun aStartSchedulesTheLookAheadAWindowAtATime() =
    runTest {
      val reads = TrackReads()
      val preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.start(FROM)

        reads.count shouldBe 2
      } finally {
        preview.release()
      }
    }

  // The playhead a tenth of a second on leaves a tenth of a second the look-ahead has not reached,
  // which is one short window rather than a full one or none.
  @Test
  fun aPumpSchedulesOnlyWhatCameInsideTheLookAheadSinceTheLast() =
    runTest {
      val reads = TrackReads()
      val preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.start(FROM)
        preview.pump(FROM + 100.milliseconds)
        reads.count shouldBe 3

        preview.pump(FROM + 100.milliseconds)
        reads.count shouldBe 3

        preview.pump(FROM + 700.milliseconds)
        reads.count shouldBe 6
      } finally {
        preview.release()
      }
    }

  @Test
  fun aStoppedPreviewSchedulesNothingFurther() =
    runTest {
      val reads = TrackReads()
      val preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.start(FROM)
        preview.stop()
        preview.pump(FROM + 2.seconds)

        reads.count shouldBe 2
      } finally {
        preview.release()
      }
    }

  // The stop lands inside the first pass.
  @Test
  fun aStopDuringAStartSchedulesNoFurtherWindow() =
    runTest {
      lateinit var preview: BrowserAudioPreview
      val reads = TrackReads(onRead = { preview.stop() })
      preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.start(FROM)

        reads.count shouldBe 1
      } finally {
        preview.release()
      }
    }

  // The new start schedules its own two windows inside the old start's first pass. The old pass must
  // not move the window cursor back to where it had reached.
  @Test
  fun aStartDuringAStalePassKeepsItsOwnWindowCursor() =
    runTest {
      lateinit var preview: BrowserAudioPreview
      var restarted = false
      val reads =
        TrackReads(onRead = {
          if (!restarted) {
            restarted = true
            launch(start = CoroutineStart.UNDISPATCHED) { preview.start(LATER) }
          }
        })
      preview = BrowserAudioPreview(reads::tracks, SourceCache())

      try {
        preview.start(FROM)
        reads.count shouldBe 3

        preview.pump(LATER)
        reads.count shouldBe 3
      } finally {
        preview.release()
      }
    }

  @Test
  fun aStartAnchorsThePlayheadWhereItWasAskedFor() =
    runTest {
      val preview = BrowserAudioPreview({ emptyList() }, SourceCache())

      try {
        preview.start(FROM)

        val position = preview.position
        withClue("the playhead read $position after a start at $FROM") {
          (position >= FROM && position < FROM + 1.seconds) shouldBe true
        }
      } finally {
        preview.release()
      }
    }

  @Test
  fun aReleasedPreviewDoesNotStartAgain() =
    runTest {
      val reads = TrackReads()
      val preview = BrowserAudioPreview(reads::tracks, SourceCache())

      preview.release()
      preview.release()
      preview.start(FROM)
      preview.pump(FROM)

      reads.count shouldBe 0
      withClue("a released preview moved its playhead to ${preview.position}") {
        (preview.position < FROM) shouldBe true
      }
    }

  // The tone steps part of the way through the source, so a seek read one to one lands on the wrong tone.
  @Test
  fun aSeekMidClipOpensTheAudioWhereTheSharedFunctionsSay() =
    runTest {
      val clip = spedClip(steppedSource(), speed = 1.5)

      listOf(0.3.seconds, 0.8.seconds).forEach { seek ->
        val slices = scheduledFrom(clip, seek)

        withClue("a seek to $seek, source ${clip.sourceTimeAt(seek)}") {
          slices.first().opensAtSeconds shouldBe (seek.toDouble(DurationUnit.SECONDS) plusOrMinus FRAME_SECONDS)
          val expected = if (clip.sourceTimeAt(seek) < TONE_STEP) LOW_HZ else HIGH_HZ
          slices.joined().dominantHz(RATE) shouldBe (expected plusOrMinus DOMINANT_STEP_HZ)
        }
      }
    }

  // Each pump asks for a short slice, so a stretcher that started over per slice would click at every join.
  @Test
  fun slicesOfASpedClipJoinWithNoClickOrGap() =
    runTest {
      listOf(1.0 / 32.0, 0.7, 1.5, 32.0).forEach { speed ->
        val source = MediaSource.Bytes(makeClipWithAudio(frames = if (speed > 10.0) 480 else 30))
        val clip = spedClip(source, speed, end = if (speed > 10.0) 16.seconds else 1.seconds)

        val slices = scheduledFrom(clip, Duration.ZERO, pumps = 2)

        withClue("at speed $speed") {
          slices.zipWithNext().forEach { (before, after) ->
            after.opensAtSeconds shouldBe
              ((before.opensAtSeconds + before.frames.toDouble() / RATE) plusOrMinus JOIN_TOLERANCE_SECONDS)
          }
          val samples = slices.joined()
          val largest = (1 until samples.size).maxOf { abs(samples[it] - samples[it - 1]) }
          withClue("the largest step was $largest") { (largest < MAX_STEP) shouldBe true }
        }
      }
    }

  @Test
  fun everySliceOfASpedClipAddsUpToTheSharedOutputLength() =
    runTest {
      val clip = spedClip(steppedSource(), speed = 1.5)

      val slices = scheduledFrom(clip, Duration.ZERO, pumps = 20)

      slices.sumOf { it.frames } shouldBe
        audioFramesOf(outputLengthOf(clip.sourceDuration, clip.speed), RATE).toInt()
    }

  // The export decodes a clip at speed 1 straight through, so the preview has to hear the same samples.
  @Test
  fun aClipAtSpeedOneIsNeverStretched() =
    runTest {
      val clip = spedClip(steppedSource(), speed = 1.0)

      scheduledFrom(clip, Duration.ZERO, pumps = 2).shouldBeEmpty()
    }

  private suspend fun scheduledFrom(
    clip: ResolvedClip,
    from: Duration,
    pumps: Int = 0,
  ): List<StretchedAudio> {
    val sources = SourceCache()
    val slices = mutableListOf<StretchedAudio>()
    val track = ResolvedTrack(TrackContent.AudioAndVideo, looping = false, start = Duration.ZERO, clips = listOf(clip))
    val preview = BrowserAudioPreview({ listOf(track) }, sources) { slices += it }
    try {
      preview.start(from)
      repeat(pumps) { preview.pump(from + PUMP_STEP * (it + 1)) }
    } finally {
      preview.release()
      sources.close()
    }
    return slices
  }

  private suspend fun steppedSource(): MediaSource =
    MediaSource.Bytes(
      makeClipWithAudio(frames = (STEPPED_LENGTH.toDouble(DurationUnit.SECONDS) * 30).roundToInt()) { seconds ->
        if (seconds.seconds < TONE_STEP) LOW_HZ else HIGH_HZ
      },
    )

  private fun spedClip(
    source: MediaSource,
    speed: Double,
    end: Duration = STEPPED_LENGTH,
  ): ResolvedClip {
    val length = outputLengthOf(end, speed)
    return ResolvedClip(
      source = source,
      info =
        MediaInfo(
          duration = end,
          video = null,
          audio = AudioTrackInfo(trackCodecOf("opus"), RATE, 1, null),
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = end,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, length),
      startsAtKeyFrame = true,
      span = TimeRange.of(Duration.ZERO, length),
      sourceIndex = 0,
      speed = speed,
    )
  }

  private fun List<StretchedAudio>.joined(): FloatArray {
    val joined = FloatArray(sumOf { it.frames })
    var at = 0
    forEach { slice ->
      slice.channels[0].copyInto(joined, at)
      at += slice.frames
    }
    return joined
  }

  private class TrackReads(
    private val onRead: () -> Unit = {},
  ) {
    var count: Int = 0
      private set

    fun tracks(): List<ResolvedTrack> {
      count++
      onRead()
      return emptyList()
    }
  }

  private companion object {
    val FROM = 5.seconds
    val LATER = 8.seconds
    const val RATE = 48_000
    const val LOW_HZ = 440.0
    const val HIGH_HZ = 880.0
    const val FRAME_SECONDS = 1.0 / RATE
    const val JOIN_TOLERANCE_SECONDS = 1e-9
    const val MAX_STEP = 0.05f
    val STEPPED_LENGTH = 2.seconds
    val TONE_STEP = 1.seconds
    val PUMP_STEP = 250.milliseconds
  }
}
