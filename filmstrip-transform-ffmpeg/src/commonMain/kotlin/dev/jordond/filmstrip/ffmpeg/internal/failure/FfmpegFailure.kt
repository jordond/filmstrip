package dev.jordond.filmstrip.ffmpeg.internal.failure

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.diagnostics.PlatformFailure
import dev.jordond.filmstrip.ffmpeg.internal.process.LIVE_FEED_FAILED
import dev.jordond.filmstrip.ffmpeg.internal.process.SPAWN_FAILED

/**
 * The name ffmpeg registers its [dev.jordond.filmstrip.diagnostics.BackendInfo] under.
 *
 * A failure the player or the export engine reports names its backend with this, so a listener can match the event
 * to the backend info.
 */
@InternalFilmstripApi
public const val FFMPEG_BACKEND: String = "ffmpeg"

/**
 * What a failed ffmpeg run says about itself, for the failure event a player or an export reports.
 *
 * The code is what the process exited with, as the operating system reported it. A run that never spawned or whose
 * live feed failed has no exit code of its own, so it carries none. ffmpeg names no codec and no cause chain, and what
 * it wrote to stderr is already the error's message.
 *
 * @param exitCode What the run exited with, or one of the values that stand in for a run with no exit code.
 */
@InternalFilmstripApi
public fun ffmpegExitFailure(exitCode: Int): PlatformFailure =
  PlatformFailure(
    backend = FFMPEG_BACKEND,
    code = exitCode.takeUnless { it == LIVE_FEED_FAILED || it == SPAWN_FAILED },
  )
