package dev.jordond.filmstrip.media3.internal.bounds

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where each window of a composition's clips is read from, which has to reach the frame a clip that plays on from the
 * one before it shows on its first slot.
 */
class ClipReadsTest {
  private val source = MediaSource.of("a.mp4")

  // The run of one source cut at 585 ms, its first clip closing a quarter of a slot before slot 14 and the second
  // opening there, so the second reads 8.3 ms before its trim on its first slot.
  private val head = clip(110.milliseconds, 585.milliseconds, at = Duration.ZERO)
  private val playsOn = clip(585.milliseconds, 1_100.milliseconds, at = 475.milliseconds, runStart = 110.milliseconds)
  private val standalone = clip(585.milliseconds, 1_100.milliseconds, at = 990.milliseconds)
  private val window = ClipWindow(source, 585.milliseconds, 1_100.milliseconds)

  @Test
  fun aWindowOpensAtTheEarliestTimeAnyClipOverItReads() {
    val reads = listOf(head, playsOn, standalone).readsFrom(30)

    reads.getValue(window) shouldBe playsOn.sourceReadAtItsFirstSlot()
    (reads.getValue(window) < 585.milliseconds) shouldBe true
  }

  @Test
  fun aWindowOpensAtTheSameTimeWhicheverClipOverItComesLast() {
    listOf(listOf(playsOn, standalone), listOf(standalone, playsOn)).forEach { clips ->
      clips.readsFrom(30).getValue(window) shouldBe playsOn.sourceReadAtItsFirstSlot()
    }
  }

  @Test
  fun aWindowOfAClipThatPlaysOnFromNothingOpensAtItsTrimStart() {
    val reads = listOf(head, standalone).readsFrom(30)

    reads.getValue(window) shouldBe 585.milliseconds
    reads.getValue(ClipWindow(source, 110.milliseconds, 585.milliseconds)) shouldBe 110.milliseconds
  }

  // The slot nearest 475 ms is slot 14 at 466.666 ms, 8.334 ms before the span opens.
  private fun ResolvedClip.sourceReadAtItsFirstSlot(): Duration = start - (span.start - 466_666.microseconds)

  private fun clip(
    start: Duration,
    end: Duration,
    at: Duration,
    runStart: Duration = start,
  ): ResolvedClip =
    ResolvedClip(
      source = source,
      info =
        MediaInfo(
          duration = 6.seconds,
          video =
            VideoTrackInfo(
              codedSize = FRAME,
              displaySize = FRAME,
              rotationDegrees = 0,
              pixelAspectRatio = 1f,
              frameRate = 30f,
              codec = trackCodecOf("avc1"),
              bitDepth = 8,
              colorSpace = ColorSpace.Bt709,
              hdrTransfer = null,
              bitrate = null,
              hasAlpha = false,
            ),
          audioTracks = emptyList(),
          isExportable = true,
        ),
      start = start,
      end = end,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, end - start),
      startsAtKeyFrame = false,
      span = TimeRange.of(at, at + (end - start)),
      sourceIndex = 0,
      runStart = runStart,
    )

  private companion object {
    val FRAME = Size(640, 360)
  }
}
