@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackBuilder
import dev.jordond.filmstrip.edit.TrackContent
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/**
 * Names one extra track of a [Sequence] for as long as the sequence holds it.
 */
@JvmInline
public value class TrackId(
  public val value: Int,
)

/**
 * One track that plays alongside the clips of the edit, with every knob that is the track's own in the library.
 *
 * The library plays video from the first track only, so an extra track is always an audio bed, like music or a
 * voice over.
 *
 * @property id The name the sequence knows this track by.
 * @property label The name the editor shows for it.
 */
@Stable
public class TrackEdit internal constructor(
  public val id: TrackId,
  public val label: String,
) {
  private val items: SnapshotStateList<ClipEdit> = mutableStateListOf()

  val clips: List<ClipEdit>
    get() = items

  var startSeconds: Float by mutableStateOf(0f)
  var looping: Boolean by mutableStateOf(false)
  var fadeInSeconds: Float by mutableStateOf(0f)
  var fadeOutSeconds: Float by mutableStateOf(0f)
  var gain: Float by mutableStateOf(1f)

  /**
   * The clips the track plays, which leaves out any the device could not read.
   */
  val playable: List<ClipEdit>
    get() = items.filter { it.failure == null }

  /**
   * What the track's level is in the library, which is no change while the gain sits at one.
   */
  val level: AudioLevel
    get() = if (gain == 1f) AudioLevel.Inherit else AudioLevel.Volume(gain)

  internal fun add(clip: ClipEdit) {
    items += clip
  }

  /**
   * Lowers the track onto the library's [Track], or null while nothing on it can play.
   *
   * @param open Whether an untrimmed clip is left without a trim, the way an edit is written. False trims it to
   *   the whole probed file instead, which lets the library say how long the track runs.
   */
  fun track(open: Boolean = true): Track? {
    val playing = playable
    if (playing.isEmpty()) return null
    return TrackBuilder(TrackContent.Audio)
      .apply {
        playing.forEach { addClip(it.clip(open = open)) }
        startAt(startSeconds.asSeconds())
        looping(looping)
        fadeIn(fadeInSeconds.asSeconds())
        fadeOut(fadeOutSeconds.asSeconds())
        audio(level)
      }.build()
  }

  /**
   * Puts every knob of the track back where it started and keeps its clips.
   */
  fun reset() {
    startSeconds = 0f
    looping = false
    fadeInSeconds = 0f
    fadeOutSeconds = 0f
    gain = 1f
    items.forEach { it.reset() }
  }
}

/**
 * Every clip the sequence holds, the ones of the edit first and then each track's.
 */
internal fun Sequence.everyClip(): List<ClipEdit> = clips + tracks.flatMap { it.clips }

/**
 * How long the clips of the edit run end to end, whatever the tracks beside them do. A clip that has not been
 * probed counts for nothing.
 */
internal val Sequence.clipsDuration: Duration
  get() = clips.fold(Duration.ZERO) { total, clip -> total + (clip.duration ?: Duration.ZERO) }
