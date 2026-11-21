package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlin.time.Duration

/**
 * A sequence holding one video clip per source, none of them probed.
 */
internal fun clipsOf(vararg sources: MediaSource): Sequence =
  Sequence().apply { sources.forEachIndexed { index, source -> add(ClipContent.Video(source), "clip $index") } }

/**
 * Appends a video clip over [source] that the device has said runs [duration].
 */
internal fun Sequence.addProbed(
  source: MediaSource,
  duration: Duration,
): ClipEdit = add(ClipContent.Video(source), "clip").also { it.probed(ProbeResult.Success(audioOnly(duration))) }

private fun audioOnly(duration: Duration): MediaInfo = MediaInfo(duration, null, emptyList(), true)
