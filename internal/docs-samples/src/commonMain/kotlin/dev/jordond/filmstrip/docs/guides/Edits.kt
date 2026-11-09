package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun clipSpeed(): Composition {
  // region clip-speed
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/wave.mp4")) {
        trim(2.seconds..8.seconds)
        speed(0.5)
        fadeIn(1.seconds)
      }
    }
  // endregion
  return composition
}

internal fun singleClip(): Composition {
  // region single-clip
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
    }
  // endregion
  return composition
}

internal fun trimmedClips(): Composition {
  // region trimmed-clips
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/intro.mp4")) {
        trim(start = 2.seconds, endExclusive = 6.seconds)
      }
      clip(MediaSource.of("/path/to/main.mp4")) {
        trim(10.seconds..40.seconds)
        fadeOut(1.seconds)
      }
    }
  // endregion
  return composition
}

internal fun snappedTrim(): Composition {
  // region snap-within
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4")) {
        trim(start = 12.seconds, endExclusive = 30.seconds)
        snapWithin(500.milliseconds)
      }
    }
  // endregion
  return composition
}

internal fun musicBed(): Composition {
  // region music-bed
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4")) {
        audio(AudioLevel.Volume(0.5f))
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

internal fun removeAudio(): Composition {
  // region remove-audio
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/clip.mp4"))
      audio(AudioSpec.Remove)
    }
  // endregion
  return composition
}
