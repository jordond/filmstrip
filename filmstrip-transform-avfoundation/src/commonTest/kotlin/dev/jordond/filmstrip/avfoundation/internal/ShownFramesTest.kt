package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.avfoundation.fixture
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.interop.SampleTimes
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TICK
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.avfoundation.writeOffGridClip
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
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetTrackSegment
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.segments
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How [insertShown] cuts a clip into segments of a composition track.
 */
@OptIn(ExperimentalForeignApi::class)
class ShownFramesTest {
  // apple_export_a.mp4 runs at 30 fps throughout, and at every speed no slot's source time sits halfway between two
  // frames, so one read from a start shifted half a frame on shows every slot its nearest frame. At speed 1 that read
  // is the clip itself.
  @Test
  fun `lays a constant rate source at a constant speed as one scaled segment`() =
    runTest {
      val path = fixture(FIXTURE)
      val sourceTrack = sourceTrackOf(path)

      listOf(SLOWED, 1.0, SPED_UP).forEach { speed ->
        val clip = clipOf(path, trim = speed.seconds, speed = speed)
        val track = videoTrack()

        track.insertShown(sourceTrack, clip, outputSlotsOf(clip.span, FRAME_RATE), FRAME_RATE)

        val segments = track.segments.filterIsInstance<AVAssetTrackSegment>()
        withClue("at $speed") {
          segments.size shouldBe 1
          segments.single().timeMapping.useContents {
            val opens = source.start.readValue().toDuration()
            withClue("the read opens at $opens") { (opens < 1.seconds / FRAME_RATE).shouldBeTrue() }
            val played = source.duration.readValue().toDuration()
            val laid = target.duration.readValue().toDuration()
            withClue("$played of source plays over $laid") {
              ((played - laid * speed).absoluteValue <= MEDIA_TICK).shouldBeTrue()
            }
          }
        }
      }
    }

  @Test
  fun `refuses a clip whose frame times cannot be read`() =
    runTest {
      val path = fixture(FIXTURE)
      val clip = clipOf(path, trim = 1.seconds, speed = 1.0)
      val track = videoTrack()

      val refused =
        assertFailsWith<AvLoweringFailure> {
          track.insertShown(sourceTrackOf(path), clip, outputSlotsOf(clip.span, FRAME_RATE), FRAME_RATE) { _, _ ->
            null
          }
        }

      refused.reason shouldContain "frame times"
      track.segments.shouldBeEmpty()
    }

  // The first two slots hold frame 0, read off the source, and the rest hold a frame read from before it opens.
  @Test
  fun `takes back what a clip laid when a later segment is refused`() =
    runTest {
      val path = fixture(FIXTURE)
      val clip = clipOf(path, trim = 200.milliseconds, speed = 1.0)
      val track = videoTrack()
      val samples =
        SampleTimes(
          times = listOf(Duration.ZERO, 100.milliseconds),
          readTicks = longArrayOf(0, -MEDIA_TIMESCALE.toLong()),
          endTick = 2L * MEDIA_TIMESCALE,
          reachesEnd = true,
        )

      val refused =
        assertFailsWith<AvLoweringFailure> {
          track.insertShown(sourceTrackOf(path), clip, outputSlotsOf(clip.span, FRAME_RATE), FRAME_RATE) { _, _ ->
            samples
          }
        }

      refused.reason shouldContain "refused"
      track.segments.shouldBeEmpty()
    }

  // Frames 1 and 2 sit 150 us apart inside the tick 40 ms opens, and both are read from the tick after it, which shows
  // frame 2. Slot 1's nearest frame is frame 1, which no tick shows, so it holds frame 2, 150 us from it, where the
  // frame before sits a whole frame off.
  @Test
  fun `holds the later frame when the nearest shares its tick with the next`() =
    runTest {
      val path = NSTemporaryDirectory() + "filmstrip-shown-frames-one-tick.mov"
      withContext(Dispatchers.Default) {
        writeOffGridClip(
          path,
          frames = PAIRED_TIMES.size,
          endsAt = PAIRED_TIMES.last() + PAIRED_GAP,
          timescale = MICROS,
          frameTime = { CMTimeMake(value = PAIRED_TIMES[it].inWholeMicroseconds, timescale = MICROS) },
        )
      }
      val clip = clipOf(path, trim = PAIRED_TIMES[PAIRED_TIMES.lastIndex - 2], speed = 1.0)
      val slots = outputSlotsOf(clip.span, PAIRED_RATE)
      val track = videoTrack()

      track.insertShown(sourceTrackOf(path), clip, slots, PAIRED_RATE)

      val nearest =
        slots.map { slot ->
          val target = sourceTimeOfSlot(Duration.ZERO, Duration.ZERO, 1.0, slot, PAIRED_RATE)
          checkNotNull(nearestSourceFrame(PAIRED_TIMES, target))
        }
      withClue("slot 1's nearest frame is the earlier of the two in one tick") { nearest[1] shouldBe 1 }
      val shown = slots.map { track.shownAt(outputSlotTime(it, PAIRED_RATE)) }
      shown shouldBe nearest.map { if (it == 1) 2 else it }
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

  // The frame AVFoundation shows at time, the last of PAIRED_TIMES at or before where the segment holding it reads.
  private fun AVMutableCompositionTrack.shownAt(time: Duration): Int {
    val read =
      segments.filterIsInstance<AVAssetTrackSegment>().firstNotNullOf { segment ->
        segment.timeMapping.useContents {
          val opens = target.start.readValue().toDuration()
          val closes = CMTimeRangeGetEnd(target.readValue()).toDuration()
          val played = source.duration.readValue().toDuration()
          val laid = target.duration.readValue().toDuration()
          if (time < opens || time >= closes) {
            null
          } else {
            source.start.readValue().toDuration() + (time - opens) * (played / laid)
          }
        }
      }
    return PAIRED_TIMES.indexOfLast { it <= read }
  }

  private fun sourceTrackOf(path: String): AVAssetTrack =
    AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
      .tracksWithMediaType(AVMediaTypeVideo)
      .single() as AVAssetTrack

  private fun videoTrack(): AVMutableCompositionTrack =
    AVMutableComposition
      .composition()
      .addMutableTrackWithMediaType(AVMediaTypeVideo, kCMPersistentTrackID_Invalid)
      .let(::checkNotNull)

  private fun clipOf(
    path: String,
    trim: Duration,
    speed: Double,
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of(path),
      info =
        MediaInfo(
          duration = 2.seconds,
          video =
            VideoTrackInfo(
              codedSize = SIZE,
              displaySize = SIZE,
              rotationDegrees = 0,
              pixelAspectRatio = 1f,
              frameRate = FRAME_RATE.toFloat(),
              codec = trackCodecOf("avc1"),
              bitDepth = 8,
              colorSpace = ColorSpace.Bt709,
              hdrTransfer = null,
              bitrate = null,
            ),
          audio = null,
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = trim,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, trim),
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, outputLengthOf(trim, speed)),
      sourceIndex = 0,
      speed = speed,
    )

  private companion object {
    const val FIXTURE = "apple_export_a.mp4"
    const val FRAME_RATE = 30
    val SIZE = Size(640, 360)

    // Three and seven fifths of a frame per slot, so a slot's source time lands a fifth of a frame from the nearest.
    const val SLOWED = 0.6
    const val SPED_UP = 1.4

    const val PAIRED_RATE = 25
    const val MICROS = 1_000_000
    val PAIRED_GAP = 40.milliseconds
    val PAIRED_TIMES =
      listOf(0, 40_050, 40_200, 80_000, 120_000, 160_000, 200_000, 240_000).map { it.microseconds }
  }
}
