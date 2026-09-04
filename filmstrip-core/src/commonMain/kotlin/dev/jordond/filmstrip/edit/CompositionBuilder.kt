package dev.jordond.filmstrip.edit

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration

/**
 * Scopes filmstrip's builder receivers, so an inner block cannot silently call an outer one's
 * members.
 */
@DslMarker
public annotation class FilmstripDsl

/**
 * Describes an edit.
 *
 * The value every [dev.jordond.filmstrip.Filmstrip] operation takes. An edit is data, so it is built
 * without an instance and can be handed to any of them.
 *
 * @param block Builds the edit.
 * @return The described edit.
 */
public fun compositionOf(block: CompositionBuilder.() -> Unit): EditComposition =
  CompositionBuilder().apply(block).build()

/**
 * Builds an [EditComposition].
 *
 * [clip] appends to the primary track, which is the only track most edits have, so a single-track
 * composition never names one. [track] adds a layer that plays alongside it.
 *
 * Each lambda-taking method has a non-lambda counterpart, [addClip], [addTrack] and [addEffects],
 * for callers that cannot use a receiver lambda.
 */
@FilmstripDsl
public class CompositionBuilder public constructor() {
  private val primary = mutableListOf<Clip>()
  private val extraTracks = mutableListOf<Track>()
  private val effects = mutableListOf<EffectSpec>()
  private var audio: AudioSpec = AudioSpec.Keep
  private var fill: Fill = Fill.Black

  /**
   * Appends a clip to the primary track, reading from [source] and configured by [block].
   */
  public fun clip(
    source: MediaSource,
    block: ClipBuilder.() -> Unit = {},
  ): CompositionBuilder = apply { primary += ClipBuilder(source).apply(block).build() }

  /**
   * Appends a still image to the primary track, held for [duration] and configured by [block].
   */
  @ExperimentalFilmstripApi
  public fun image(
    image: ImageSource,
    duration: Duration,
    block: ClipBuilder.() -> Unit = {},
  ): CompositionBuilder = clip(MediaSource.Image(image, duration), block)

  /**
   * Appends an already-built clip to the primary track.
   */
  public fun addClip(clip: Clip): CompositionBuilder = apply { primary += clip }

  /**
   * Adds a track that plays alongside the primary one, configured by [block].
   *
   * Its audio is mixed with everything else and its video is composited under the primary track.
   *
   * @param content Which media streams this track contributes. A music bed is
   *   [TrackContent.Audio].
   */
  @ExperimentalFilmstripApi
  public fun track(
    content: TrackContent = TrackContent.AudioAndVideo,
    block: TrackBuilder.() -> Unit,
  ): CompositionBuilder = apply { extraTracks += TrackBuilder(content).apply(block).build() }

  /**
   * Adds an already-built track alongside the primary one.
   */
  @ExperimentalFilmstripApi
  public fun addTrack(track: Track): CompositionBuilder = apply { extraTracks += track }

  /**
   * Adds effects that apply to the composited output, after every track's own.
   */
  public fun effects(block: EffectsBuilder.() -> Unit): CompositionBuilder =
    apply { effects += EffectsBuilder().apply(block).build() }

  /**
   * Adds already-built composition-level effects.
   */
  public fun addEffects(effects: List<EffectSpec>): CompositionBuilder = apply { this.effects += effects }

  /**
   * Sets what happens to the composition's audio once every track has been mixed.
   */
  public fun audio(spec: AudioSpec): CompositionBuilder = apply { audio = spec }

  /**
   * Sets what fills the frame where no clip's pixels land.
   */
  public fun fill(fill: Fill): CompositionBuilder = apply { this.fill = fill }

  /**
   * Freezes what has been described.
   */
  public fun build(): EditComposition {
    // An edit written entirely as tracks has no primary clips, and an empty leading track would
    // hand the output frame to nothing.
    val tracks =
      if (primary.isEmpty() && extraTracks.isNotEmpty()) {
        extraTracks.toList()
      } else {
        listOf(Track(primary.toList())) + extraTracks
      }

    return EditComposition(tracks = tracks, effects = effects.toList(), audio = audio, fill = fill)
  }
}

/**
 * Builds one [Track].
 *
 * @param content Which media streams this track contributes.
 */
@FilmstripDsl
public class TrackBuilder public constructor(
  private val content: TrackContent = TrackContent.AudioAndVideo,
) {
  private val clips = mutableListOf<Clip>()
  private val effects = mutableListOf<EffectSpec>()
  private var audio: AudioLevel = AudioLevel.Inherit
  private var fadeIn: Duration = Duration.ZERO
  private var fadeOut: Duration = Duration.ZERO
  private var start: Duration = Duration.ZERO
  private var looping: Boolean = false

  /**
   * Appends a clip reading from [source], configured by [block].
   */
  public fun clip(
    source: MediaSource,
    block: ClipBuilder.() -> Unit = {},
  ): TrackBuilder = apply { clips += ClipBuilder(source).apply(block).build() }

  /**
   * Appends a still image, held for [duration] and configured by [block].
   */
  @ExperimentalFilmstripApi
  public fun image(
    image: ImageSource,
    duration: Duration,
    block: ClipBuilder.() -> Unit = {},
  ): TrackBuilder = clip(MediaSource.Image(image, duration), block)

  /**
   * Appends an already-built clip.
   */
  public fun addClip(clip: Clip): TrackBuilder = apply { clips += clip }

  /**
   * Adds effects that apply to every clip on this track, after each clip's own.
   */
  public fun effects(block: EffectsBuilder.() -> Unit): TrackBuilder =
    apply { effects += EffectsBuilder().apply(block).build() }

  /**
   * Adds already-built track effects.
   */
  public fun addEffects(effects: List<EffectSpec>): TrackBuilder = apply { this.effects += effects }

  /**
   * Sets what happens to this track's audio before it is mixed.
   */
  public fun audio(level: AudioLevel): TrackBuilder = apply { audio = level }

  /**
   * Ramps this track's audio up from silence over [duration], starting where the track does.
   *
   * The ramp rises to whatever [audio] set rather than past it, and composes with [fadeOut]. Where
   * the two are written makes no difference, since both are folded in once the track is built.
   */
  @ExperimentalFilmstripApi
  public fun fadeIn(duration: Duration): TrackBuilder = apply { fadeIn = duration }

  /**
   * Ramps this track's audio down to silence over the [duration] before it ends.
   *
   * A looping track has no end to measure against, so the fade is dropped there.
   */
  @ExperimentalFilmstripApi
  public fun fadeOut(duration: Duration): TrackBuilder = apply { fadeOut = duration }

  /**
   * Holds the track off until [start] into the composition.
   */
  public fun startAt(start: Duration): TrackBuilder = apply { this.start = start }

  /**
   * Repeats the track until the longest non-looping one ends.
   */
  public fun looping(looping: Boolean = true): TrackBuilder = apply { this.looping = looping }

  /**
   * Freezes what has been described.
   */
  public fun build(): Track =
    Track(
      clips = clips.toList(),
      content = content,
      effects = effects.toList(),
      audio = audio.withFades(fadeIn, if (looping) Duration.ZERO else fadeOut),
      start = start,
      looping = looping,
    )
}

/**
 * Builds one [Clip].
 */
@FilmstripDsl
public class ClipBuilder public constructor(
  private val source: MediaSource,
) {
  private var trim: TimeRange? = null
  private val effects = mutableListOf<EffectSpec>()
  private var audio: AudioLevel = AudioLevel.Inherit
  private var fadeIn: Duration = Duration.ZERO
  private var fadeOut: Duration = Duration.ZERO
  private var snapWithin: Duration = Duration.ZERO

  /**
   * Keeps only `[start, endExclusive)` of the source.
   */
  public fun trim(
    start: Duration,
    endExclusive: Duration,
  ): ClipBuilder = apply { trim = TimeRange(start, endExclusive) }

  /**
   * Keeps only [range] of the source.
   */
  public fun trim(range: TimeRange): ClipBuilder = apply { trim = range }

  /**
   * Keeps only [range] of the source, treating its end as exclusive.
   */
  public fun trim(range: ClosedRange<Duration>): ClipBuilder = apply { trim = TimeRange(range) }

  /**
   * Lets the cut move back up to [duration] to reach a sync sample, so a trimmed export can copy
   * its streams rather than re-encode them.
   *
   * Zero, the default, keeps the cut where [trim] put it.
   */
  public fun snapWithin(duration: Duration): ClipBuilder = apply { snapWithin = duration }

  /**
   * Adds effects that apply to this clip only, before any track or composition effect.
   */
  public fun effects(block: EffectsBuilder.() -> Unit): ClipBuilder =
    apply { effects += EffectsBuilder().apply(block).build() }

  /**
   * Adds already-built clip effects.
   */
  public fun addEffects(effects: List<EffectSpec>): ClipBuilder = apply { this.effects += effects }

  /**
   * Sets what happens to this clip's audio.
   */
  public fun audio(level: AudioLevel): ClipBuilder = apply { audio = level }

  /**
   * Ramps this clip's audio up from silence over [duration] from its first kept sample.
   *
   * The ramp rises to whatever [audio] set rather than past it, and composes with [fadeOut]. Where
   * the two are written makes no difference, since both are folded in once the clip is built.
   */
  @ExperimentalFilmstripApi
  public fun fadeIn(duration: Duration): ClipBuilder = apply { fadeIn = duration }

  /**
   * Ramps this clip's audio down to silence over the [duration] before its last kept sample.
   *
   * Measured against the trim, so retrimming the clip moves the fade with it.
   */
  @ExperimentalFilmstripApi
  public fun fadeOut(duration: Duration): ClipBuilder = apply { fadeOut = duration }

  /**
   * Freezes what has been described.
   */
  public fun build(): Clip =
    Clip(
      source = source,
      trim = trim,
      effects = effects.toList(),
      audio = audio.withFades(fadeIn, fadeOut),
      snapWithin = snapWithin,
    )
}

/**
 * Collects effect declarations.
 *
 * The order you add effects in does not matter. They are sorted into filmstrip's canonical pipeline
 * order at plan time, so a watermark added before a crop is still drawn after the crop.
 */
@FilmstripDsl
public class EffectsBuilder public constructor() {
  private val effects = mutableListOf<EffectSpec>()

  /**
   * Adds one effect, built-in or third-party.
   */
  public fun add(effect: EffectSpec): EffectsBuilder = apply { effects += effect }

  /**
   * Adds several effects.
   */
  public fun addAll(effects: List<EffectSpec>): EffectsBuilder = apply { this.effects += effects }

  /**
   * Freezes what has been declared, in declaration order. Canonical ordering happens at plan time.
   */
  public fun build(): List<EffectSpec> = effects.toList()
}
