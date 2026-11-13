@file:OptIn(BetaInteropApi::class)

package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.PrimedAudio
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.timeRange
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Lays an untrimmed audio-only MP4 whose AAC track opens on an encoder delay its edit list skips, which AVFoundation
 * reads a little shorter than the movie header the probe reports, and checks its audio reaches the preview and the
 * export at the header's length.
 */
@OptIn(ExperimentalForeignApi::class)
class ApplePrimedAudioMediaTest {
  private val path = NSTemporaryDirectory() + "filmstrip-primed-aac-${NSUUID().UUIDString}.mp4"
  private val output = NSTemporaryDirectory() + "filmstrip-primed-aac-out-${NSUUID().UUIDString}.mp4"

  init {
    val bytes = PrimedAudio.mp4()
    bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }.writeToFile(path, true)
  }

  @AfterTest
  fun remove() {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)
  }

  @Test
  fun `the preview lays the whole of the audio`() =
    runTest(timeout = TIMEOUT) {
      val resolved = resolved(composition())

      val lowered = withContext(Dispatchers.Default) { resolved.toAvComposition() }

      val audioEnds =
        lowered.composition.tracksWithMediaType(AVMediaTypeAudio).map {
          CMTimeRangeGetEnd((it as AVAssetTrack).timeRange).toDuration()
        }
      resolved.duration shouldBe PrimedAudio.presentedLength
      withClue("the audio tracks end at $audioEnds") { audioEnds.max() shouldBe PrimedAudio.presentedLength }
    }

  @Test
  fun `the export carries the audio at the length the movie header gives`() =
    runTest(timeout = TIMEOUT) {
      val filmstrip = Filmstrip { avFoundationBackend() }

      val success = filmstrip.exportOrFail(composition(), ExportSpec(), output)

      withClue("the audio track did not survive") { success.info.audio.shouldNotBeNull() }
      val decoded = (audioOf(output).samples.size.toDouble() / PROBE_SAMPLE_RATE).seconds
      withClue("the export decodes to $decoded and reports ${success.info.duration}") {
        (success.info.duration - PrimedAudio.presentedLength).absoluteValue shouldBeLessThanOrEqualTo AAC_FRAME
        // Reading an Apple AAC export back decodes about two frames short of its track, however the clip was laid.
        (PrimedAudio.presentedLength - decoded).absoluteValue shouldBeLessThanOrEqualTo AAC_FRAME * 3
      }
    }

  private fun composition(): Composition =
    compositionOf {
      clip(MediaSource.of(fixture("apple_export_a.mp4")))
      track(TrackContent.Audio) { clip(MediaSource.of(path)) }
    }

  private suspend fun resolved(composition: Composition): ResolvedComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, ExportSpec())) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    }

  private companion object {
    val TIMEOUT = 3.minutes
    val AAC_FRAME: Duration = (PrimedAudio.AAC_FRAME.toDouble() / PrimedAudio.RATE).seconds
  }
}
