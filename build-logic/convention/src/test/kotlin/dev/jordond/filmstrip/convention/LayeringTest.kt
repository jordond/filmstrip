package dev.jordond.filmstrip.convention

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pins how far the backend's exception to the `androidx.media3.exoplayer` ban reaches: the track selection and media
 * source factory types a track choice needs, and nothing that builds or drives a player.
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

  private fun backendRejects(fqn: String): Boolean =
    isForbiddenImport(fqn, Layer.Backend.forbiddenImports.orEmpty(), Layer.Backend.allowedImports)
}
