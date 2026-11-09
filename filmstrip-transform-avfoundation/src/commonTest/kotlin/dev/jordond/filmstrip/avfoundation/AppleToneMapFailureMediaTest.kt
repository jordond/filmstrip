package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * A compositor request that throws, read off the export it fails.
 *
 * Every failure in a request takes the same way out, so a plan that misreads a clip's transfer stands in for the ones a
 * test cannot force, like a missing renderer or a kernel that will not run.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleToneMapFailureMediaTest {
  @Test
  fun `a frame of another transfer than the plan fails the export by name`() =
    runTest(timeout = TIMEOUT) {
      val failure = failureOf(clipOf(hdrFixture(HdrTransfer.Hlg)), planned = HdrTransfer.Pq)

      failure.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
      failure.message shouldContain "clip of Pq"
      failure.message shouldContain "frame of Hlg"
    }

  @Test
  fun `an sdr frame under a plan of hdr fails the export by name`() =
    runTest(timeout = TIMEOUT) {
      val failure = failureOf(clipOf(fixture(SDR_FIXTURE)), planned = HdrTransfer.Hlg)

      failure.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
      failure.message shouldContain "clip of Hlg"
      failure.message shouldContain "frame of SDR"
    }

  private suspend fun failureOf(
    composition: Composition,
    planned: HdrTransfer,
  ): FilmstripError =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().build()
      val real = chainedProber(components)
      val prober = misreading(real, planned)
      val driver = AvDriver(prober)
      val resolved =
        avFoundationExportEngine(components, prober, driver)
          .resolve(composition, SPEC)
          .shouldBeInstanceOf<ResolveResult.Resolved>()
      driver
        .export(resolved.composition, MediaSink.of(NSTemporaryDirectory() + "filmstrip-tone-map-mismatch.mp4"))
        .toList()
        .last()
        .shouldBeInstanceOf<ExportStatus.Failure>()
        .error
    }

  private fun misreading(
    real: MediaProber,
    transfer: HdrTransfer,
  ): MediaProber =
    MediaProber { source ->
      when (val probed = real.probe(source)) {
        is ProbeResult.Failure -> probed
        is ProbeResult.Success -> ProbeResult.Success(probed.info.carrying(transfer))
      }
    }

  private fun MediaInfo.carrying(transfer: HdrTransfer): MediaInfo =
    MediaInfo(
      duration = duration,
      video =
        video?.run {
          VideoTrackInfo(
            codedSize,
            displaySize,
            rotationDegrees,
            pixelAspectRatio,
            frameRate,
            codec,
            bitDepth,
            colorSpace,
            transfer,
            bitrate,
          )
        },
      audio = audio,
      isExportable = isExportable,
    )

  private fun clipOf(path: String): Composition =
    compositionOf { clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) } }

  private companion object {
    const val SDR_FIXTURE = "apple_export_a.mp4"
    val CLIP = 200.milliseconds
    val TIMEOUT = 2.minutes
    val SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}
