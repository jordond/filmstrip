package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.EnvelopeAnchor
import dev.jordond.filmstrip.edit.EnvelopePoint
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun levels(): Composition {
  // region levels
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/interview.mp4")) {
        audio(AudioLevel.Volume(1.5f))
      }
      clip(MediaSource.of("/path/to/broll.mp4")) {
        audio(AudioLevel.Mute)
      }
      audio(AudioSpec.Volume(0.8f))
    }
  // endregion
  return composition
}

internal fun fades(): Composition {
  // region fades
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4")) {
        trim(start = 5.seconds, endExclusive = 25.seconds)
        audio(AudioLevel.Volume(0.5f))
        fadeIn(1.seconds)
        fadeOut(2.seconds)
      }
    }
  // endregion
  return composition
}

internal fun envelope(): Composition {
  // region envelope
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4")) {
        audio(
          AudioLevel.Envelope(
            listOf(
              EnvelopePoint(at = 4.seconds, gain = 1f),
              EnvelopePoint(at = 5.seconds, gain = 0.2f),
              EnvelopePoint(at = 3.seconds, gain = 0.2f, from = EnvelopeAnchor.End),
              EnvelopePoint(at = Duration.ZERO, gain = 1f, from = EnvelopeAnchor.End),
            ),
          ),
        )
      }
    }
  // endregion
  return composition
}

internal fun bed(): Composition {
  // region bed
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      track(TrackContent.Audio) {
        clip(MediaSource.of("/path/to/voice-over.m4a"))
        startAt(3.seconds)
      }
      track(TrackContent.Audio) {
        clip(MediaSource.of("/path/to/music.m4a"))
        audio(AudioLevel.Volume(0.3f))
        looping()
        fadeOut(2.seconds)
      }
    }
  // endregion
  return composition
}
