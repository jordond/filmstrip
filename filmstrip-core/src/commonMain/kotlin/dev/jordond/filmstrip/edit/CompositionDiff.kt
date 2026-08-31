package dev.jordond.filmstrip.edit

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration

/**
 * How much of a playback graph a change between two compositions forces a backend to rebuild.
 *
 * Ordered from cheapest to most expensive: [Equal] needs no work at all, [ParametersOnly] can swap
 * rendering parameters in place, and [Structural] needs the underlying graph rebuilt.
 */
@InternalFilmstripApi
public enum class CompositionDiff {
  /**
   * Nothing that affects playback or rendering is different.
   */
  Equal,

  /**
   * The timeline is unchanged, but an effect, the fill or the audio mix differs.
   */
  ParametersOnly,

  /**
   * The timeline itself differs: which clips play, in what order, or when.
   */
  Structural,
}

/**
 * Classifies what changed between [previous] and [next].
 *
 * A null [previous] is always [CompositionDiff.Structural], since there is nothing yet to reuse.
 * Otherwise the timeline is checked first: every track's clips, their sources, their trims and
 * their timing. Any difference there is structural no matter what else changed. With the timeline
 * unchanged, a difference in an effect, the fill or the audio mix is
 * [CompositionDiff.ParametersOnly]. Effects are compared in their canonical pipeline order, so
 * declaring the same effects in a different order that resolves to the same pipeline reports no
 * difference at all.
 */
@InternalFilmstripApi
public fun diff(
  previous: EditComposition?,
  next: EditComposition,
): CompositionDiff {
  if (previous == null || previous.timeline() != next.timeline()) return CompositionDiff.Structural
  return if (previous.rendering() == next.rendering()) CompositionDiff.Equal else CompositionDiff.ParametersOnly
}

/**
 * A hash of everything that decides what one rendered frame of [this] looks like.
 *
 * Folds in the timeline shape and the resolved effect chain, composition, track and clip effects
 * alike, each in canonical pipeline order, plus the fill. Audio never contributes, since nothing
 * about it reaches a rendered frame. Pure and depends only on this composition's own values, so a
 * freshly rebuilt composition with the same edit hashes the same as the original, and a crop or
 * rotation committed to a new value hashes differently even though it changes no clip, track or
 * source.
 */
@InternalFilmstripApi
public fun EditComposition.effectsRevision(): Long {
  var hash = HASH_SEED
  hash = hash.mixed(timeline())
  hash = hash.mixed(effects.inCanonicalOrder())
  hash = hash.mixed(fill)
  for (track in tracks) {
    hash = hash.mixed(track.effects.inCanonicalOrder())
    for (clip in track.clips) {
      hash = hash.mixed(clip.effects.inCanonicalOrder())
    }
  }
  return hash
}

private fun EditComposition.timeline(): Timeline =
  Timeline(
    tracks.map { track ->
      TimelineTrack(
        content = track.content,
        start = track.start,
        looping = track.looping,
        clips = track.clips.map { TimelineClip(it.source, it.trim) },
      )
    },
  )

private fun EditComposition.rendering(): Rendering =
  Rendering(
    compositionEffects = effects.inCanonicalOrder(),
    fill = fill,
    audio = audio,
    tracks =
      tracks.map { track ->
        TrackRendering(
          effects = track.effects.inCanonicalOrder(),
          audio = track.audio,
          clips = track.clips.map { ClipRendering(it.effects.inCanonicalOrder(), it.audio) },
        )
      },
  )

private data class Timeline(
  val tracks: List<TimelineTrack>,
)

private data class TimelineTrack(
  val content: TrackContent,
  val start: Duration,
  val looping: Boolean,
  val clips: List<TimelineClip>,
)

private data class TimelineClip(
  val source: MediaSource,
  val trim: TimeRange?,
)

private data class Rendering(
  val compositionEffects: List<EffectSpec>,
  val fill: Fill,
  val audio: AudioSpec,
  val tracks: List<TrackRendering>,
)

private data class TrackRendering(
  val effects: List<EffectSpec>,
  val audio: AudioLevel,
  val clips: List<ClipRendering>,
)

private data class ClipRendering(
  val effects: List<EffectSpec>,
  val audio: AudioLevel,
)

// A 64-bit multiplicative accumulator over each part's hashCode, so the revision has more headroom
// than the 32 bits a single hashCode() call would leave a cache key this collision-sensitive.
private const val HASH_SEED = -3750763034362895579L
private const val HASH_MULTIPLIER = 1099511628211L

private fun Long.mixed(part: Any?): Long = (this xor (part?.hashCode()?.toLong() ?: 0L)) * HASH_MULTIPLIER
