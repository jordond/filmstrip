package dev.jordond.filmstrip.transform.internal.audio

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.audioFramesOf
import kotlin.time.Duration

/**
 * The index of the audio frame at [time] on the composition timeline, counted at [sampleRate] from the start of the
 * composition, to the nearest frame.
 *
 * Every host of an audio kernel reads the position it hands the kernel from here, so a kernel that reads its position
 * is the same function of composition time on every backend.
 */
@InternalFilmstripApi
public fun timelineFrameOf(
  time: Duration,
  sampleRate: Int,
): Long = audioFramesOf(time, sampleRate)
