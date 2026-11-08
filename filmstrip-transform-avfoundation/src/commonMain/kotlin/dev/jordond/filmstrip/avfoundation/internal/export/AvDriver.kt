package dev.jordond.filmstrip.avfoundation.internal.export

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.AudioStretchFailure
import dev.jordond.filmstrip.avfoundation.internal.ClipAudioRender
import dev.jordond.filmstrip.avfoundation.internal.StretchedAudio
import dev.jordond.filmstrip.avfoundation.internal.chain.sdPrimariesKernelLimit
import dev.jordond.filmstrip.avfoundation.internal.chain.toneMapKernelLimit
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.interop.syncSampleAtOrBefore
import dev.jordond.filmstrip.avfoundation.internal.renderClipAudio
import dev.jordond.filmstrip.avfoundation.internal.toNSURL
import dev.jordond.filmstrip.avfoundation.internal.writer.AvDestination
import dev.jordond.filmstrip.avfoundation.internal.writer.DestinationResult
import dev.jordond.filmstrip.avfoundation.internal.writer.WriterRun
import dev.jordond.filmstrip.avfoundation.internal.writer.avEncoderCapabilities
import dev.jordond.filmstrip.avfoundation.internal.writer.resolveDestination
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.AppleScratch
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ExportDriver
import dev.jordond.filmstrip.transform.internal.probe.ProcessProbe
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import kotlin.time.Duration

/**
 * The Apple export driver, on an AVFoundation reader and writer.
 *
 * What the encoders can do is read once per process in [avEncoderCapabilities], what runs is
 * built in [toAvComposition], and driving the two pumps is [WriterRun].
 */
internal class AvDriver(
  private val prober: MediaProber,
  private val kernelLimit: () -> String? = { toneMapKernelLimit() },
  private val bytesRoot: String = AppleScratch.defaultRoot,
  private val renderClip: suspend (ClipAudioRender) -> Unit = ::renderClipAudio,
) : ExportDriver {
  override suspend fun capabilities(): DeviceCapabilities = processEncoders.read()

  override fun renderCapabilities(
    outputSize: Size,
    hdr: Boolean,
  ): RenderCapabilities = coreImageRenderCapabilities(outputSize, hdr)

  override fun toneMapLimit(sdrSources: List<MediaSource>): String? = kernelLimit()

  override suspend fun syncSampleAtOrBefore(
    source: MediaSource,
    cut: Duration,
  ): Duration? =
    withContext(Dispatchers.Default) {
      val url =
        try {
          withContext(Dispatchers.IO) { source.toNSURL(bytesRoot) }
        } catch (_: AvLoweringFailure) {
          return@withContext null
        } catch (_: MediaBytesFileFailure) {
          // The lowering resolves the same path next and refuses with the reason.
          return@withContext null
        }
      val track =
        AVURLAsset(uRL = url, options = null)
          .tracksWithMediaType(AVMediaTypeVideo)
          .firstOrNull() as? AVAssetTrack
      track?.syncSampleAtOrBefore(cut)
    }

  override fun unclaimed(specId: String): String =
    "No resolver claimed $specId on the Apple backend. Register the built-in catalogue with " +
      "builtInEffects(), or add a resolver that recognises RenderApi.CoreImage."

  override fun export(
    resolved: ResolvedComposition,
    to: MediaSink,
  ): Flow<ExportStatus> =
    flow {
      val stretched = StretchedAudio(bytesRoot, renderClip)
      // Rendering a clip's audio is the export's own first step and can run a long while before a frame is written.
      val startsEarly = stretched.mayRender(resolved)
      if (startsEarly) emit(ExportStatus.Started)
      try {
        when (val prepared = withContext(Dispatchers.IO) { prepare(resolved, to, stretched) }) {
          is Preparation.Failed -> {
            emit(ExportStatus.Failure(prepared.error))
          }
          is Preparation.Ready -> {
            emitAll(
              WriterRun(
                prober = prober,
                resolved = resolved,
                composition = prepared.composition,
                destination = prepared.destination,
                announcesStart = !startsEarly,
              ).run(),
            )
          }
        }
      } finally {
        stretched.discard()
      }
    }

  /**
   * Settles everything that can fail before a reader or a writer exists, so the run itself has one
   * shape. A clip at speed has its audio rendered here, which is the slow part of this for a long clip and stops when
   * the collector cancels.
   */
  private suspend fun prepare(
    resolved: ResolvedComposition,
    to: MediaSink,
    stretched: StretchedAudio,
  ): Preparation {
    val composition =
      try {
        stretched.renderAll(resolved, bytesRoot)
        resolved.toAvComposition(sdKernelLimit = { sdPrimariesKernelLimit() }, bytesRoot, stretched)
      } catch (failure: AudioStretchFailure) {
        return Preparation.Failed(failure.error)
      } catch (failure: AvLoweringFailure) {
        return Preparation.Failed(FilmstripError.InvalidComposition(failure.reason))
      } catch (failure: MediaBytesFileFailure) {
        return Preparation.Failed(FilmstripError.SourceUnreadable(failure.source, failure.message))
      }

    return when (val destination = resolveDestination(to)) {
      is DestinationResult.Failed -> Preparation.Failed(destination.error)
      is DestinationResult.Ready -> Preparation.Ready(composition, destination.destination)
    }
  }

  private sealed interface Preparation {
    class Ready(
      val composition: AvComposition,
      val destination: AvDestination,
    ) : Preparation

    class Failed(
      val error: FilmstripError,
    ) : Preparation
  }
}

// avEncoderCapabilities opens a VideoToolbox session per codec down the resolution ladder plus a Main10 probe, and
// what those sessions report belongs to the device rather than to whichever driver asked.
private val processEncoders = ProcessProbe { avEncoderCapabilities() }

/**
 * What Core Image can draw for an offline export.
 */
internal fun coreImageRenderCapabilities(
  outputSize: Size,
  hdr: Boolean,
): RenderCapabilities =
  RenderCapabilities(
    api = RenderApi.Metal,
    supportsFragmentShader = true,
    supportsComputeShader = true,
    // True only once the grade reaches the encoder, which is what [hdr] answers.
    supportsHdr = hdr,
    colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt601, ColorSpace.Bt2020),
    maxTextureSize = maxOf(outputSize.width, outputSize.height, MAX_TEXTURE_FLOOR),
    features =
      buildSet {
        add(RenderFeature.MultipassRender)
        add(RenderFeature.TextRendering)
        // The tone map runs on a Metal kernel, and a device that cannot compile one is refused by toneMapLimit. It is
        // not [hdr], which is true when a grade is being kept and so when nothing is being tone-mapped at all.
        add(RenderFeature.HdrToneMapping)
      },
  )

private const val MAX_TEXTURE_FLOOR = 8_192
