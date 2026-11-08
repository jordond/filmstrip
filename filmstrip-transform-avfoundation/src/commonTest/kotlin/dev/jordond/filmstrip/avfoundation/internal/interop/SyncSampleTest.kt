package dev.jordond.filmstrip.avfoundation.internal.interop

import dev.jordond.filmstrip.avfoundation.fixture
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeSorted
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderSampleReferenceOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFBooleanRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.kCMSampleAttachmentKey_NotSync
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The sync sample [syncSampleAtOrBefore] steps a cursor to, against every sample an asset reader hands back for the
 * same track.
 *
 * The reader walks the track in decode order and marks each sample a decoder can open on, so it is the reference for a
 * track whose frames are reordered. Each track is asked at every sample time and in the middle of every gap between
 * two.
 */
@OptIn(ExperimentalForeignApi::class)
class SyncSampleTest {
  @Test
  fun `finds the same sync sample as a reader on a track with reordered frames`() =
    runTest {
      val track = videoTrackOf(fixture(REORDERED_FIXTURE))
      val reference = readerSamplesOf(track)

      withClue("the fixture's samples arrive in presentation order, so none are reordered") {
        reference.map { it.at }.shouldNotBeSorted()
      }
      track.shouldAgreeWith(reference)
    }

  @Test
  fun `finds the same sync sample as a reader on a track with many sync samples`() =
    runTest {
      val track = videoTrackOf(fixture(LONG_FIXTURE))
      val reference = readerSamplesOf(track)

      withClue("the fixture holds one sync sample, so no cut has an earlier one to skip") {
        reference.count { it.sync } shouldBeGreaterThan 1
      }
      track.shouldAgreeWith(reference)
    }

  // The frame shown just before the fixture's second sync sample sits a whole group of frames past the first.
  @Test
  fun `refuses a walk past more samples than its cap`() =
    runTest {
      val track = videoTrackOf(fixture(LONG_FIXTURE))
      val reference = readerSamplesOf(track)
      val syncs = reference.filter { it.sync }.map { it.at }.sorted()
      val probe = syncs[1] - 1.nanoseconds
      withClue("the walk from the probe back to the first sync sample holds no more frames than the cap") {
        reference.count { it.at > syncs[0] && it.at <= probe } shouldBeGreaterThan SMALL_CAP
      }

      val refused = assertFailsWith<AvLoweringFailure> { track.syncSampleAtOrBefore(probe, cap = SMALL_CAP) }

      refused.reason shouldContain "$SMALL_CAP frames"
    }

  @Test
  fun `stops the walk when the caller is cancelled`() =
    runTest {
      val track = videoTrackOf(fixture(LONG_FIXTURE))
      var walked = false

      runCatching {
        coroutineScope {
          cancel()
          track.syncSampleAtOrBefore(1.seconds)
          walked = true
        }
      }

      walked.shouldBeFalse()
    }

  private suspend fun AVAssetTrack.shouldAgreeWith(reference: List<ReaderSample>) {
    val shown = reference.map { it.at }.distinct().sorted()
    val probes = shown + shown.zipWithNext { a, b -> a + (b - a) / 2 } + (shown.last() + 1.nanoseconds)
    probes.forEach { probe ->
      val expected = reference.filter { it.sync && it.at <= probe }.maxOfOrNull { it.at }
      withClue("the sync sample at or before $probe") { syncSampleAtOrBefore(probe) shouldBe expected }
    }
  }

  private fun videoTrackOf(path: String): AVAssetTrack =
    AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
      .tracksWithMediaType(AVMediaTypeVideo)
      .first() as AVAssetTrack

  // A sample's time on the track's own timeline and whether a decoder could open on it.
  private data class ReaderSample(
    val at: Duration,
    val sync: Boolean,
  )

  // Every sample the reader hands back, in decode order.
  @OptIn(BetaInteropApi::class)
  private fun readerSamplesOf(track: AVAssetTrack): List<ReaderSample> {
    val reader = AVAssetReader.assetReaderWithAsset(track.asset.shouldNotBeNull(), error = null).shouldNotBeNull()
    val output = AVAssetReaderSampleReferenceOutput(track = track)
    reader.addOutput(output)
    reader.startReading().shouldBeTrue()
    val shift = track.editShift()
    val read = mutableListOf<ReaderSample>()
    while (true) {
      val more =
        autoreleasepool {
          val buffer = output.copyNextSampleBuffer() ?: return@autoreleasepool false
          if (CMSampleBufferGetNumSamples(buffer) > 0) {
            val at = (CMSampleBufferGetPresentationTimeStamp(buffer).toDuration() + shift).coerceAtLeast(Duration.ZERO)
            read += ReaderSample(at, buffer.isSyncSample())
          }
          CFRelease(buffer)
          true
        }
      if (!more) break
    }
    reader.cancelReading()
    return read
  }

  // A sample is marked only when it is not a sync sample, so one carrying no attachments is one.
  private fun CMSampleBufferRef.isSyncSample(): Boolean {
    val attachments = CMSampleBufferGetSampleAttachmentsArray(this, createIfNecessary = false) ?: return true
    if (CFArrayGetCount(attachments) == 0L) return true
    val sample: CFDictionaryRef = CFArrayGetValueAtIndex(attachments, 0)?.reinterpret() ?: return true
    val notSync: CFBooleanRef =
      CFDictionaryGetValue(sample, kCMSampleAttachmentKey_NotSync)?.reinterpret() ?: return true
    return !CFBooleanGetValue(notSync)
  }

  private companion object {
    const val REORDERED_FIXTURE = "apple_export_a.mp4"
    const val LONG_FIXTURE = "apple_export_long.mp4"
    const val SMALL_CAP = 5
  }
}
