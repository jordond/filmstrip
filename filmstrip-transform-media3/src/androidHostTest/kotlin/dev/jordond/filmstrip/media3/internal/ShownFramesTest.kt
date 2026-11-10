package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.util.SpeedProviderUtil.SpeedProviderMapper
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.bounds.FrameBounds
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Where media3's frame rate cap stops keeping the samples [ShownFrames] lays on their slots, which is media3's limit.
 *
 * The cap keeps a frame at or past a tick a whole microsecond step of the output rate past the last one, and a slot is
 * not a whole number of microseconds, so a long sped up item's slots run ahead of those ticks. Each item here is cut
 * from a source at the output rate and played at speed 2, so every slot skips one sample and that sample is written a
 * microsecond after the one the slot shows.
 */
class ShownFramesTest {
  @Test
  fun `media3's frame rate cap keeps every shown sample on its slot until the slots run a slot ahead of its ticks`() {
    listOf(30 to 55.minutes, 60 to 6.minutes).forEach { (rate, past) ->
      val step = MICROS_PER_SECOND / rate
      val onset =
        (0L..Long.MAX_VALUE).first { slot ->
          outputSlotTime(slot, rate).inWholeMicroseconds - slot * step + SKIPPED >= step
        }
      val clip = clipOf(rate, outputSlotTime(onset + RUN_PAST, rate) * SPEED)
      val samples = (0L until (onset + RUN_PAST + 2) * SKIP_EVERY).map { (it * MICROS_PER_SECOND / rate).microseconds }
      val bounds = FrameBounds(samples, samples.last() + (MICROS_PER_SECOND / rate).microseconds)
      val frames = checkNotNull(ShownFrames.of(clip, bounds, rate))
      val mapper = SpeedProviderMapper(SlotPace(frames, SPEED))

      val decoded =
        samples
          .filter { it in frames.opens..<frames.closes }
          .map { mapper.getAdjustedTimeUs((it - frames.opens).inWholeMicroseconds) }
      var tick = -1L
      val kept =
        decoded.filter { written ->
          val keeps = tick < 0 || written >= tick
          if (keeps) tick = if (tick < 0) written + step else tick + step
          keeps
        }
      val expected = outputSlotsOf(clip.span, rate).map { outputSlotTime(it, rate).inWholeMicroseconds }

      withClue("at $rate fps") {
        outputSlotTime(onset, rate) shouldBeGreaterThan past
        kept.take(onset.toInt() + 1) shouldBe expected.take(onset.toInt() + 1)
        kept[onset.toInt() + 1] shouldNotBe expected[onset.toInt() + 1]
      }
    }
  }

  private fun clipOf(
    rate: Int,
    length: Duration,
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of("clip.mp4"),
      info =
        MediaInfo(
          duration = length + 1.seconds,
          video =
            VideoTrackInfo(
              codedSize = FRAME,
              displaySize = FRAME,
              rotationDegrees = 0,
              pixelAspectRatio = 1f,
              frameRate = rate.toFloat(),
              codec = trackCodecOf("avc1"),
              bitDepth = 8,
              colorSpace = ColorSpace.Bt709,
              hdrTransfer = null,
              bitrate = null,
              hasAlpha = false,
            ),
          audio = null,
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = length,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, outputLengthOf(length, SPEED)),
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, outputLengthOf(length, SPEED)),
      sourceIndex = 0,
      speed = SPEED,
    )

  private companion object {
    const val SPEED = 2.0
    const val SKIP_EVERY = 2L
    const val SKIPPED = 1L
    const val RUN_PAST = 50L
    const val MICROS_PER_SECOND = 1_000_000L
    val FRAME = Size(320, 180)
  }
}
