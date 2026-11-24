package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.failure.FFMPEG_BACKEND
import dev.jordond.filmstrip.ffmpeg.internal.failure.ffmpegExitFailure
import dev.jordond.filmstrip.ffmpeg.internal.process.LIVE_FEED_FAILED
import dev.jordond.filmstrip.ffmpeg.internal.process.SPAWN_FAILED
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What a failed ffmpeg run carries onto the failure event.
 *
 * The exit code is kept as the process reported it, and a run with no exit code of its own carries none.
 */
class FfmpegExitFailureTest {
  @Test
  fun `a run that exited keeps its exit code`() {
    val failure = ffmpegExitFailure(234)

    failure.backend shouldBe FFMPEG_BACKEND
    failure.code shouldBe 234
  }

  @Test
  fun `a run that never spawned or lost its live feed carries no code`() {
    ffmpegExitFailure(SPAWN_FAILED).code.shouldBeNull()
    ffmpegExitFailure(LIVE_FEED_FAILED).code.shouldBeNull()
  }
}
