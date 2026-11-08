package dev.jordond.filmstrip.avfoundation.internal.interop

import dev.jordond.filmstrip.avfoundation.fixture
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.avfoundation.writeOffGridClip
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeSorted
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.useContents
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderSampleReferenceOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.timeRange
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeAdd
import platform.CoreMedia.CMTimeConvertScale
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreMedia.kCMTimeRoundingMethod_RoundTowardPositiveInfinity
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The sample cursor [sampleTimesAround] steps against every sample an asset reader hands back for the same track.
 *
 * The reader walks the track in decode order, so it is the reference for a track whose frames are reordered, and for
 * one whose frames fall between two ticks.
 */
@OptIn(ExperimentalForeignApi::class)
class SampleCursorTest {
  @Test
  fun `reads the same samples as a reader on a track with reordered frames`() =
    runTest {
      val track = videoTrackOf(fixture(REORDERED_FIXTURE))
      val reference = readerSamplesOf(track)

      withClue("the fixture's samples arrive in presentation order, so none are reordered") {
        reference.decodeOrder.shouldNotBeSorted()
      }
      track.shouldAgreeWith(reference)
    }

  @Test
  fun `reads the same samples as a reader on a 29_97 track`() =
    runTest {
      val path = NSTemporaryDirectory() + "filmstrip-sample-cursor-ntsc.mov"
      writeOffGridClip(
        path,
        frames = NTSC_FRAMES,
        endsAt = (NTSC_FRAME * NTSC_FRAMES).toDouble().seconds / NTSC_TIMESCALE,
        timescale = NTSC_TIMESCALE,
        frameTime = { CMTimeMake(value = NTSC_FRAME * it, timescale = NTSC_TIMESCALE) },
      )
      val track = videoTrackOf(path)

      track.shouldAgreeWith(readerSamplesOf(track))
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

  // The whole track reads every sample the reader does, and a window in the middle reads an unbroken run of them that
  // holds the window.
  private suspend fun AVAssetTrack.shouldAgreeWith(reference: ReaderSamples) {
    val end = CMTimeRangeGetEnd(timeRange).toDuration()
    val whole = sampleTimesAround(Duration.ZERO, end).shouldNotBeNull()
    whole.times.map { it.inWholeNanoseconds } shouldBe reference.nanos
    whole.times.indices.map(whole::readTickOf) shouldBe reference.ticks

    val from = whole.times[whole.times.size / 3]
    val to = whole.times[whole.times.size * 2 / 3]
    val window = sampleTimesAround(from, to).shouldNotBeNull()
    val opens = reference.nanos.indexOf(window.times.first().inWholeNanoseconds)
    window.times.map { it.inWholeNanoseconds } shouldBe reference.nanos.subList(opens, opens + window.times.size)
    window.times.shouldContainAll(from, to)
    window.times.indices.map(window::readTickOf) shouldBe reference.ticks.subList(opens, opens + window.times.size)
  }

  private fun videoTrackOf(path: String): AVAssetTrack =
    AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
      .tracksWithMediaType(AVMediaTypeVideo)
      .first() as AVAssetTrack

  // Each sample's time as the reader hands them back, and every distinct one in presentation order with its tick.
  private class ReaderSamples(
    val decodeOrder: List<Long>,
    val nanos: List<Long>,
    val ticks: List<Long>,
  )

  @OptIn(BetaInteropApi::class)
  private fun readerSamplesOf(track: AVAssetTrack): ReaderSamples {
    val reader = AVAssetReader.assetReaderWithAsset(track.asset.shouldNotBeNull(), error = null).shouldNotBeNull()
    val output = AVAssetReaderSampleReferenceOutput(track = track)
    reader.addOutput(output)
    reader.startReading().shouldBeTrue()
    val shift = track.editShiftTime()
    val read = mutableListOf<Pair<Long, Long>>()
    while (true) {
      val more =
        autoreleasepool {
          val buffer = output.copyNextSampleBuffer() ?: return@autoreleasepool false
          if (CMSampleBufferGetNumSamples(buffer) > 0) {
            val composed = CMTimeAdd(CMSampleBufferGetPresentationTimeStamp(buffer), shift)
            val tick =
              CMTimeConvertScale(composed, MEDIA_TIMESCALE, kCMTimeRoundingMethod_RoundTowardPositiveInfinity)
                .useContents { value }
            read += composed.toDuration().inWholeNanoseconds to tick.coerceAtLeast(0)
          }
          CFRelease(buffer)
          true
        }
      if (!more) break
    }
    reader.cancelReading()
    val presented = read.distinctBy { it.first }.sortedBy { it.first }
    return ReaderSamples(read.map { it.first }, presented.map { it.first }, presented.map { it.second })
  }

  private companion object {
    const val REORDERED_FIXTURE = "apple_export_a.mp4"
    const val NTSC_TIMESCALE = 30_000
    const val NTSC_FRAME = 1_001L
    const val NTSC_FRAMES = 12
  }
}
