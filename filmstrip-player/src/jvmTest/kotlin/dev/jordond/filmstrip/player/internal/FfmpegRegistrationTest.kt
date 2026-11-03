package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.internal.thumbnail.FfmpegThumbnailPlanner
import dev.jordond.filmstrip.player.jvmFixtureComposition
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration

class FfmpegRegistrationTest {
  @Test
  fun `a preview with no ffmpeg backend registered is refused as a missing backend`(): Unit =
    runBlocking {
      val planner = FfmpegPreviewPlanner(ComponentRegistry.Builder().build())

      planner
        .plan(jvmFixtureComposition(), PreviewQualityPolicy.Full)
        .shouldBeInstanceOf<PlanResult.Refused<PlaybackError>>()
        .error shouldBe MISSING_FFMPEG.toPlaybackError()
    }

  @Test
  fun `a thumbnail with no ffmpeg backend registered is refused as a missing backend`(): Unit =
    runBlocking {
      val planner = FfmpegThumbnailPlanner(ComponentRegistry.Builder().build())
      val request = ThumbnailRequest(jvmFixtureComposition(), Duration.ZERO, null, 0L, precise = true)

      planner.lower(request).shouldBeInstanceOf<PlanResult.Refused<*>>().error shouldBe MISSING_FFMPEG
    }

  @Test
  fun `a preview runs the toolchain its registration names`(): Unit =
    runBlocking {
      val components = Filmstrip { ffmpegBackend(FfmpegConfig(executablePath = "/nonexistent/ffmpeg")) }.components

      FfmpegPreviewPlanner(components)
        .plan(jvmFixtureComposition(), PreviewQualityPolicy.Full)
        .shouldBeInstanceOf<PlanResult.Refused<PlaybackError>>()
        .error
        .shouldBeInstanceOf<PlaybackError.DecoderUnavailable>()
    }
}
