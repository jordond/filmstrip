package dev.jordond.filmstrip.avfoundation.internal.interop

import dev.jordond.filmstrip.avfoundation.fixture
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.writeOffGridClip
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reading a track's sample times off its sample table, and the tick each is read from on a composition track.
 */
@OptIn(ExperimentalForeignApi::class)
class SampleTimesMediaTest {
  @Test
  fun `reads the frames either side of a window in presentation order`() =
    runTest {
      val frame = 1.seconds / FIXTURE_RATE
      val samples =
        videoTrackOf(
          fixture(FIXTURE),
        ).sampleTimesAround(frame * 10 + 5.milliseconds, frame * 20).shouldNotBeNull()

      samples.times
        .zipWithNext()
        .all { (before, after) -> before < after }
        .shouldBeTrue()
      (samples.times.first() <= frame * 10).shouldBeTrue()
      (samples.times.last() >= frame * 20).shouldBeTrue()
      val tenth = samples.times.indexOfFirst { abs((it - frame * 10).inWholeMicroseconds) <= 1 }
      samples.readTickOf(tenth) shouldBe MEDIA_TIMESCALE / FIXTURE_RATE * 10L
      samples.lastTickOf(tenth) shouldBe MEDIA_TIMESCALE / FIXTURE_RATE * 11L - 1
    }

  // 29.97 fps lands every frame but the first between two ticks, so each is read from the tick after it and a read
  // one tick earlier still shows the frame before.
  @Test
  fun `reads a frame between two ticks from the tick after it`() =
    runTest {
      val path = NSTemporaryDirectory() + "filmstrip-sample-times-ntsc.mov"
      writeOffGridClip(
        path,
        frames = NTSC_FRAMES,
        endsAt = ntscTime(NTSC_FRAMES),
        timescale = NTSC_TIMESCALE,
        frameTime = { CMTimeMake(value = NTSC_FRAME * it, timescale = NTSC_TIMESCALE) },
      )

      val samples = videoTrackOf(path).sampleTimesAround(Duration.ZERO, ntscTime(NTSC_FRAMES - 1)).shouldNotBeNull()

      withClue(samples.times) { samples.times.size shouldBe NTSC_FRAMES }
      samples.times.forEachIndexed { index, time ->
        (abs((time - ntscTime(index)).inWholeMicroseconds) <= 1).shouldBeTrue()
        val exact = NTSC_FRAME * index * MEDIA_TIMESCALE
        val tick = (exact + NTSC_TIMESCALE - 1) / NTSC_TIMESCALE
        samples.readTickOf(index) shouldBe tick
        if (index > 0) samples.lastTickOf(index - 1) shouldBe tick - 1
      }
      samples.lastTickOf(NTSC_FRAMES - 1) shouldBe samples.endTick - 1
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

  // Samples 1 to 3 are read from one tick, where AVFoundation shows sample 3.
  @Test
  fun `shows the last of the samples read from one tick in place of the others`() {
    val samples =
      SampleTimes(
        times = listOf(0, 1, 2, 3, 4).map { it.milliseconds },
        readTicks = longArrayOf(0, 5, 5, 5, 9),
        endTick = 12,
        reachesEnd = true,
      )

    (0..4).map(samples::shownOf) shouldBe listOf(0, 3, 3, 3, 4)
  }

  // The window and the second read either side of it hold dozens of the fixture's frames, far more than the cap.
  @Test
  fun `refuses a scan holding more samples than its cap`() =
    runTest {
      val frame = 1.seconds / FIXTURE_RATE

      val refused =
        assertFailsWith<AvLoweringFailure> {
          videoTrackOf(fixture(FIXTURE)).sampleTimesAround(frame * 10, frame * 20, cap = SMALL_CAP)
        }

      refused.reason shouldContain "$SMALL_CAP frames"
    }

  @Test
  fun `stops the scan when the caller is cancelled`() =
    runTest {
      val track = videoTrackOf(fixture(FIXTURE))
      var scanned = false

      runCatching {
        coroutineScope {
          cancel()
          track.sampleTimesAround(Duration.ZERO, 1.seconds)
          scanned = true
        }
      }

      scanned.shouldBeFalse()
    }

  private fun videoTrackOf(path: String): AVAssetTrack =
    AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
      .tracksWithMediaType(AVMediaTypeVideo)
      .first() as AVAssetTrack

  private fun ntscTime(frame: Int): Duration = 1.seconds * (NTSC_FRAME * frame).toDouble() / NTSC_TIMESCALE

  private companion object {
    const val FIXTURE = "apple_export_a.mp4"
    const val FIXTURE_RATE = 30
    const val NTSC_TIMESCALE = 30_000
    const val NTSC_FRAME = 1_001L
    const val NTSC_FRAMES = 12
    const val SMALL_CAP = 5
  }
}
