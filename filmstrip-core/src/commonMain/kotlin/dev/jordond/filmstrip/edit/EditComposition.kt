package dev.jordond.filmstrip.edit

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * A complete, immutable description of an edit: what to read, what to cut, what to apply.
 *
 * Output format lives in [ExportSpec], so one composition can be previewed and then exported at several qualities.
 * Pass the same value to `preview()` and to `export()`.
 *
 * Clips live on [Track]s, which play at the same time as each other rather than one after another.
 * Most edits have a single track and never name one. [clips] reads it and the composition builder fills it.
 * More than one track is [ExperimentalFilmstripApi].
 *
 * Serializable, so an edit list can be persisted, moved between devices, and rendered on the other platform.
 * Third-party [EffectSpec] implementations must be registered with `filmstripSerializersModule` to round-trip.
 *
 * @property tracks The layers to render, the first of which is the primary one.
 * @property effects Effects applied to the composited output, after every track's own effects.
 * @property audio What to do with the composition's audio once every track has been mixed.
 * @property fill What fills the frame where no clip's pixels land.
 */
@Serializable
@Immutable
@Poko
public class EditComposition(
  public val tracks: List<Track>,
  public val effects: List<EffectSpec> = emptyList(),
  public val audio: AudioSpec = AudioSpec.Keep,
  public val fill: Fill = Fill.Black,
) {
  /**
   * The primary track's clips, which for a single-track edit is every clip there is.
   */
  public val clips: List<Clip>
    get() = tracks.firstOrNull()?.clips ?: emptyList()

  /**
   * Total duration after trimming.
   *
   * The longest non-looping track, since tracks play together. Null while any clip's trim is
   * open-ended, and null when every track loops, which is an edit with nothing to bound it. Probe
   * the sources first if you need a number before then.
   */
  public val duration: Duration?
    get() {
      if (tracks.isEmpty()) return Duration.ZERO
      val timed = tracks.filterNot { it.looping }
      if (timed.isEmpty()) return null
      return timed.fold(Duration.ZERO) { longest, track -> maxOf(longest, track.duration ?: return null) }
    }

  /**
   * A copy whose primary track holds different clips.
   */
  public fun withClips(clips: List<Clip>): EditComposition {
    val primary = tracks.firstOrNull()?.withClips(clips) ?: Track(clips)
    return EditComposition(listOf(primary) + tracks.drop(1), effects, audio, fill)
  }

  /**
   * A copy with different tracks.
   */
  @ExperimentalFilmstripApi
  public fun withTracks(tracks: List<Track>): EditComposition =
    EditComposition(tracks = tracks, effects = effects, audio = audio, fill = fill)

  /**
   * A copy with different composition-level effects.
   */
  public fun withEffects(effects: List<EffectSpec>): EditComposition =
    EditComposition(tracks = tracks, effects = effects, audio = audio, fill = fill)

  /**
   * A copy with a different audio treatment.
   */
  public fun withAudio(audio: AudioSpec): EditComposition =
    EditComposition(tracks = tracks, effects = effects, audio = audio, fill = fill)

  /**
   * A copy with a different fill.
   */
  public fun withFill(fill: Fill): EditComposition =
    EditComposition(tracks = tracks, effects = effects, audio = audio, fill = fill)
}

/**
 * One layer of a composition: clips laid end to end, playing alongside the other tracks.
 *
 * Tracks run at the same time, so a second one is a music bed, a picture-in-picture inset or an
 * overlay reel, and its audio is mixed with the rest. The first track is the primary one: it sets
 * the output frame, and it is drawn on top.
 *
 * Track effects apply to every clip on the track, which is the cheap way to grade a run of clips at
 * once. They are lowered onto each clip individually, so an effect whose result depends on where a
 * frame sits in the track cannot be expressed here. Nothing in the built-in catalogue is like that.
 *
 * @property clips The sources to play, in order.
 * @property content Which media streams this track contributes.
 * @property effects Effects applied to every clip on this track, after each clip's own.
 * @property audio What to do with this track's audio before it is mixed.
 * @property start Where in the composition this track begins. Anything before it is silence, or
 *   black on the primary track.
 * @property looping Whether the track repeats until the longest non-looping track ends. A
 *   composition needs at least one non-looping track to have a duration at all.
 */
@Serializable
@Poko
public class Track(
  public val clips: List<Clip>,
  public val content: TrackContent = TrackContent.AudioAndVideo,
  public val effects: List<EffectSpec> = emptyList(),
  public val audio: AudioLevel = AudioLevel.Inherit,
  public val start: Duration = Duration.ZERO,
  public val looping: Boolean = false,
) {
  /**
   * How long this track runs, counting from the start of the composition.
   *
   * Null while any clip is untrimmed or open-ended and its source has not been probed. Meaningless
   * for a [looping] track, which ends when the rest of the composition does.
   */
  public val duration: Duration?
    get() = clips.fold(start) { total, clip -> total + (clip.duration ?: return null) }

  /**
   * A copy with different clips.
   */
  public fun withClips(clips: List<Clip>): Track = Track(clips, content, effects, audio, start, looping)

  /**
   * A copy with different track-level effects.
   */
  public fun withEffects(effects: List<EffectSpec>): Track = Track(clips, content, effects, audio, start, looping)

  /**
   * A copy at a different audio level.
   */
  public fun withAudio(audio: AudioLevel): Track = Track(clips, content, effects, audio, start, looping)
}

/**
 * Which media streams a track contributes to the output.
 *
 * A music bed is [Audio], a picture-in-picture inset is [Video], and anything read straight from a
 * camera roll is [AudioAndVideo]. Selecting one here drops the other before mixing, rather than
 * muting it.
 */
@Serializable
public enum class TrackContent {
  /**
   * Audio only. Video from these clips is never decoded.
   */
  Audio,

  /**
   * Video only. Audio from these clips is never decoded.
   */
  Video,

  /**
   * Both, which is what a plain video clip contributes.
   */
  AudioAndVideo,
}

/**
 * One source contributing a time range to a track.
 *
 * Clip effects run before track effects, which run before composition effects. All three are sorted
 * into filmstrip's canonical pipeline order, so the order you write them in does not matter, but
 * the scope you write them in does.
 *
 * @property source Where to read the media from.
 * @property trim The part of the source to keep, or null for all of it.
 * @property effects Effects applied to this clip only.
 * @property audio What to do with this clip's audio.
 */
@Serializable
@Poko
public class Clip(
  public val source: MediaSource,
  public val trim: TimeRange? = null,
  public val effects: List<EffectSpec> = emptyList(),
  public val audio: AudioLevel = AudioLevel.Inherit,
) {
  /**
   * How long this clip contributes, or null while it is untrimmed or open-ended and the source has
   * not been probed.
   *
   * A still needs no probe, because its source already says how long it is held, so even an
   * open-ended trim over one resolves to the length a backend lays.
   */
  public val duration: Duration?
    get() = (source as? MediaSource.Image)?.let { stillHold(it.duration, trim) } ?: trim?.duration

  /**
   * A copy trimmed to [trim], or untrimmed when null.
   */
  public fun withTrim(trim: TimeRange?): Clip = Clip(source, trim, effects, audio)

  /**
   * A copy with different clip-level effects.
   */
  public fun withEffects(effects: List<EffectSpec>): Clip = Clip(source, trim, effects, audio)

  /**
   * A copy at a different audio level.
   */
  public fun withAudio(audio: AudioLevel): Clip = Clip(source, trim, effects, audio)
}

/**
 * What to do with one clip's or one track's audio.
 *
 * Narrower than [AudioSpec]: a clip or a track can only be quieter than it was. Dropping the audio
 * track, or keeping audio while dropping video, is set on the composition. Levels multiply down the
 * scopes, and a [Mute] at any of them silences everything below it.
 */
@Serializable
public sealed interface AudioLevel {
  /**
   * Take the level from the enclosing scope, which is the track for a clip and the composition for
   * a track.
   */
  @Serializable
  @SerialName("inherit")
  public data object Inherit : AudioLevel

  /**
   * Contribute silence, without changing timing or the output's track count.
   */
  @Serializable
  @SerialName("mute")
  public data object Mute : AudioLevel

  /**
   * Contribute at a scaled gain.
   *
   * @property gain The scale to apply, where `1f` is unchanged and `0f` matches [Mute].
   */
  @Serializable
  @SerialName("volume")
  @Poko
  public class Volume(
    public val gain: Float,
  ) : AudioLevel
}

/**
 * What to do with the composition's audio, once every track has been mixed.
 *
 * [Mute] keeps a silent track in the output, [Remove] writes no audio track at all. To quieten one
 * clip or one track rather than the whole edit, see [AudioLevel].
 */
@Serializable
public sealed interface AudioSpec {
  /**
   * Pass audio through unchanged.
   */
  @Serializable
  @SerialName("keep")
  public data object Keep : AudioSpec

  /**
   * Keep the track and write silence, preserving track count and timing.
   */
  @Serializable
  @SerialName("mute")
  public data object Mute : AudioSpec

  /**
   * Drop the audio track entirely. A different operation from [Mute].
   */
  @Serializable
  @SerialName("remove")
  public data object Remove : AudioSpec

  /**
   * Keep audio and drop video.
   */
  @Serializable
  @SerialName("audioOnly")
  public data object AudioOnly : AudioSpec

  /**
   * Keep the track at a scaled gain. Unlike a player's volume, this reaches the exported file.
   *
   * @property gain The scale to apply, where `1f` is unchanged and `0f` matches [Mute].
   */
  @Serializable
  @SerialName("volume")
  @Poko
  public class Volume(
    public val gain: Float,
  ) : AudioSpec
}
