package dev.jordond.filmstrip.convention

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pins how far each exception to a layer's import ban reaches. The backend may name the track selection and media
 * source factory types a track choice needs, and nothing that builds or drives a player. Core may name the asset
 * reader and its track output, and nothing that composes, writes, exports or plays.
 */
class LayeringTest {
  @Test
  fun `a backend may name the track selection and media source factory types`() {
    listOf(
      "androidx.media3.exoplayer.trackselection.DefaultTrackSelector",
      "androidx.media3.exoplayer.trackselection.TrackSelector.Factory",
      "androidx.media3.exoplayer.trackselection.ExoTrackSelection",
      "androidx.media3.exoplayer.source.MediaSource",
      "androidx.media3.exoplayer.source.MediaSource.Factory",
      "androidx.media3.exoplayer.source.DefaultMediaSourceFactory",
      "androidx.media3.exoplayer.drm.DrmSessionManagerProvider",
      "androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy",
      "androidx.media3.exoplayer.upstream.CmcdConfiguration.Factory",
      "androidx.media3.exoplayer.util.ReleasableExecutor",
    ).forEach { fqn -> backendRejects(fqn) shouldBe false }
  }

  @Test
  fun `a backend still may not name a player or any other exoplayer type`() {
    listOf(
      "androidx.media3.exoplayer.ExoPlayer",
      "androidx.media3.exoplayer.ExoPlayer.Builder",
      "androidx.media3.exoplayer.source.ProgressiveMediaSource",
      "androidx.media3.exoplayer.source.MediaSourceFactory",
      "androidx.media3.exoplayer.source.MergingMediaSource",
      "androidx.media3.exoplayer.audio.DefaultAudioSink",
      "androidx.media3.exoplayer.LoadControl",
      "androidx.media3.exoplayer.drm.DefaultDrmSessionManager",
      "androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy",
    ).forEach { fqn -> backendRejects(fqn) shouldBe true }
  }

  @Test
  fun `the exception reaches no layer but the backend`() {
    Layer.entries
      .filter { it != Layer.Backend }
      .forEach { layer ->
        layer.allowedImports.none { it.startsWith("androidx.media3.exoplayer") } shouldBe true
      }
  }

  @Test
  fun `core may name the asset reader and its track output`() {
    listOf(
      "platform.AVFoundation.AVAssetReader",
      "platform.AVFoundation.AVAssetReaderTrackOutput",
    ).forEach { fqn -> Layer.Core.rejects(fqn) shouldBe false }
  }

  @Test
  fun `core still may not name another reader output or anything that writes or plays`() {
    listOf(
      "platform.AVFoundation.AVAssetReaderVideoCompositionOutput",
      "platform.AVFoundation.AVAssetReaderAudioMixOutput",
      "platform.AVFoundation.AVAssetWriter",
      "platform.AVFoundation.AVAssetExportSession",
      "platform.AVFoundation.AVPlayer",
      "platform.VideoToolbox.VTDecompressionSessionCreate",
    ).forEach { fqn -> Layer.Core.rejects(fqn) shouldBe true }
  }

  @Test
  fun `the asset reader reaches no layer but core`() {
    listOf(Layer.Transform, Layer.Io, Layer.IoCompose).forEach { layer ->
      layer.rejects("platform.AVFoundation.AVAssetReader") shouldBe true
      layer.rejects("platform.AVFoundation.AVAssetReaderTrackOutput") shouldBe true
    }
  }

  private fun backendRejects(fqn: String): Boolean = Layer.Backend.rejects(fqn)

  private fun Layer.rejects(fqn: String): Boolean = isForbiddenImport(fqn, forbiddenImports.orEmpty(), allowedImports)
}
