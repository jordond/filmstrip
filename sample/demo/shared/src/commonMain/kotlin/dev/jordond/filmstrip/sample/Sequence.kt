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
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.ClipBuilder
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.canonicalOrder
import dev.jordond.filmstrip.effects.geometry.kenBurns
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.motion.Easing
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/**
 * Names one clip of a [Sequence] for as long as the sequence holds it, however far it is moved.
 */
@JvmInline
public value class ClipId(
  public val value: Int,
)

/**
 * What a clip shows, which decides the knobs that apply to it.
 */
public sealed interface ClipContent {
  /**
   * A stretch of a video or audio file.
   */
  public class Video(
    public val source: MediaSource,
  ) : ClipContent

  /**
   * A still picture held on screen.
   */
  public class Image(
    public val image: ImageSource,
  ) : ClipContent

  /**
   * One flat colour held on screen.
   */
  public data object Solid : ClipContent
}

/**
 * One clip of the edit, with every knob that is the clip's own in the library.
 *
 * A video clip reads its trim, snap, speed, level and audio track. A picture or a colour reads [holdSeconds], and
 * a colour reads [color] too. Fades, the grade and the transform apply to all three, and the pan to a video or a
 * picture.
 *
 * @property id The name the sequence knows this clip by.
 * @property content What the clip shows.
 * @property label The name the editor shows for it.
 * @property preset The built-in clip this came from, or null for one the user picked.
 */
@Stable
public class ClipEdit internal constructor(
  public val id: ClipId,
  public val content: ClipContent,
  public val label: String,
  public val preset: SamplePreset? = null,
) {
  var probing: Boolean by mutableStateOf(false)
    internal set
  var probe: ProbeResult? by mutableStateOf(null)
    private set

  var trimEnabled: Boolean by mutableStateOf(false)
  var trimStartSeconds: Float by mutableStateOf(0f)
  var trimEndSeconds: Float by mutableStateOf(0f)
  var snapWithinSeconds: Float by mutableStateOf(0f)
  var speed: Double by mutableStateOf(1.0)
  var fadeInSeconds: Float by mutableStateOf(0f)
  var fadeOutSeconds: Float by mutableStateOf(0f)
  var audio: AudioLevel by mutableStateOf(AudioLevel.Inherit)
  var audioTrack: AudioTrackId? by mutableStateOf(null)
  var holdSeconds: Float by mutableStateOf(DEFAULT_HOLD_SECONDS)
  var color: Int by mutableStateOf(EditState.BLACK)
  var panEnabled: Boolean by mutableStateOf(false)
  var panFrom: NormalizedRect by mutableStateOf(NormalizedRect.Full)
  var panTo: NormalizedRect by mutableStateOf(DEFAULT_PAN_TO)
  var panEasing: Easing by mutableStateOf(Easing.EaseInOut)

  /**
   * The colour grade this clip alone gets, under the edit's.
   */
  val grade: GradeState = GradeState()

  /**
   * The turn and the mirroring this clip alone gets, before the edit's.
   */
  val transform: TransformState = TransformState()

  val info: MediaInfo?
    get() = (probe as? ProbeResult.Success)?.info

  /**
   * How long the file behind a video clip runs, or null until it has been probed. A picture or a colour has none.
   */
  val sourceDuration: Duration?
    get() = info?.duration

  /**
   * Why the clip cannot load, or null while it can or has not been probed.
   */
  val failure: String?
    get() = (probe as? ProbeResult.Failure)?.error?.message

  /**
   * How long the clip runs on the timeline, or null for a video clip that has not been probed. A clip the device
   * could not read is left out of the edit, so it runs for no time at all.
   */
  val duration: Duration?
    get() = if (failure != null) Duration.ZERO else clip(open = false).duration

  /**
   * Whether any knob of the clip has left where it started.
   */
  val touched: Boolean
    get() =
      trimEnabled || snapWithinSeconds != 0f || speed != 1.0 || fadeInSeconds != 0f || fadeOutSeconds != 0f ||
        audio != AudioLevel.Inherit || audioTrack != null || holdSeconds != DEFAULT_HOLD_SECONDS ||
        color != EditState.BLACK || panEnabled || grade.graded || transform.touched

  /**
   * Whether the clip pans, which a colour never does since every part of it looks the same.
   */
  val pans: Boolean
    get() = panEnabled && content != ClipContent.Solid

  /**
   * Records what the device said about the clip, and opens the trim over the whole file.
   */
  internal fun probed(result: ProbeResult) {
    probe = result
    trimEndSeconds = wholeSeconds()
  }

  /**
   * How much of the source the clip keeps, or null while nothing has been trimmed.
   */
  fun trimRange(): TimeRange? {
    val whole = sourceDuration
    if (!trimEnabled || whole == null) return null
    val start = trimStartSeconds.asSeconds().coerceIn(Duration.ZERO, whole)
    val end = trimEndSeconds.asSeconds().coerceIn(start, whole)
    return if (end > start) TimeRange(start, end) else null
  }

  /**
   * Where the trim starts, or zero while trimming is off.
   */
  fun trimOffset(): Duration = if (trimEnabled) trimStartSeconds.asSeconds() else Duration.ZERO

  /**
   * How far into the clip's run on the timeline [sourceTime] plays. A time the trim cuts is held at the nearer
   * end of the run.
   */
  internal fun runOffsetOf(sourceTime: Duration): Duration {
    val run = duration ?: return Duration.ZERO
    return ((sourceTime - keptStart()) / pace()).coerceIn(Duration.ZERO, run)
  }

  /**
   * The source time playing [offset] into the clip's run on the timeline, held inside the run.
   */
  internal fun sourceTimeAt(offset: Duration): Duration {
    val run = duration ?: return keptStart()
    return keptStart() + offset.coerceIn(Duration.ZERO, run) * pace()
  }

  private fun keptStart(): Duration = trimRange()?.start ?: Duration.ZERO

  private fun pace(): Double = if (content is ClipContent.Video) speed else 1.0

  /**
   * Lowers the clip onto the library's [Clip].
   *
   * @param trimmed Whether the trim is applied. False keeps the whole source, which is what a strip with trim
   *   handles on it renders against.
   * @param open Whether an untrimmed video clip is left without a trim, the way an edit is written. False trims it
   *   to the whole probed file instead, which plays the same and lets the library say how long it runs.
   * @param panned Whether the pan is applied. False keeps the frame still, which is what the rectangles of a pan
   *   are dragged over.
   */
  fun clip(
    trimmed: Boolean = true,
    open: Boolean = true,
    panned: Boolean = true,
  ): Clip =
    when (content) {
      is ClipContent.Video -> {
        ClipBuilder(content.source)
          .apply {
            val range = if (trimmed) trimRange() else null
            val whole = sourceDuration
            when {
              range != null -> trim(range)
              !open && whole != null -> trim(TimeRange(Duration.ZERO, whole))
            }
            snapWithin(snapWithinSeconds.asSeconds())
            speed(speed)
            audio(audio)
            audioTrack?.let { audioTrack(it) }
            fades()
            looks(panned)
          }.build()
      }
      is ClipContent.Image -> {
        ClipBuilder(MediaSource.Image(content.image, holdSeconds.asSeconds()))
          .apply {
            fades()
            looks(panned)
          }.build()
      }
      ClipContent.Solid -> {
        ClipBuilder(MediaSource.Solid(color, holdSeconds.asSeconds()))
          .apply {
            fades()
            looks(panned)
          }.build()
      }
    }

  private fun ClipBuilder.fades() {
    fadeIn(fadeInSeconds.asSeconds())
    fadeOut(fadeOutSeconds.asSeconds())
  }

  private fun ClipBuilder.looks(panned: Boolean) {
    val own =
      EffectsBuilder()
        .apply {
          addAll(transform.effects)
          addAll(grade.effects)
          if (panned && pans) kenBurns(panFrom, panTo, panEasing)
        }.build()
    if (own.isNotEmpty()) effects { addAll(canonicalOrder(own)) }
  }

  /**
   * Puts every knob back where it started, with the trim open over the whole file.
   */
  fun reset() {
    trimEnabled = false
    trimStartSeconds = 0f
    trimEndSeconds = wholeSeconds()
    snapWithinSeconds = 0f
    speed = 1.0
    fadeInSeconds = 0f
    fadeOutSeconds = 0f
    audio = AudioLevel.Inherit
    audioTrack = null
    holdSeconds = DEFAULT_HOLD_SECONDS
    color = EditState.BLACK
    panEnabled = false
    panFrom = NormalizedRect.Full
    panTo = DEFAULT_PAN_TO
    panEasing = Easing.EaseInOut
    grade.reset()
    transform.reset()
  }

  private fun wholeSeconds(): Float = sourceDuration?.let { it.inWholeMilliseconds / 1000f } ?: 0f

  public companion object {
    /**
     * How long a picture or a colour is held until its slider moves.
     */
    public const val DEFAULT_HOLD_SECONDS: Float = 2f

    /**
     * Where a pan ends until its rectangle is dragged, the middle half of the frame.
     */
    public val DEFAULT_PAN_TO: NormalizedRect = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)
  }
}

/**
 * The clips of the edit in the order they play, the tracks that play alongside them, and which clip the editor
 * has selected.
 *
 * This is the only owner of clip state. The export, the preview and the timeline strip all build from it.
 */
@Stable
public class Sequence {
  private val items: SnapshotStateList<ClipEdit> = mutableStateListOf()
  private val extras: SnapshotStateList<TrackEdit> = mutableStateListOf()
  private var nextId = 0
  private var nextTrackId = 0

  val clips: List<ClipEdit>
    get() = items

  /**
   * The tracks that play alongside the clips, in the order they were added.
   */
  val tracks: List<TrackEdit>
    get() = extras

  var selected: ClipId? by mutableStateOf(null)
    private set

  val selectedClip: ClipEdit?
    get() = items.firstOrNull { it.id == selected }

  /**
   * The clips the edit plays, which leaves out any the device could not read.
   */
  val playable: List<ClipEdit>
    get() = items.filter { it.failure == null }

  /**
   * The clip the output frame follows, the first playable one that carries video.
   */
  val lead: ClipEdit?
    get() = playable.firstOrNull { it.info?.video != null }

  /**
   * How long the sequence runs, or null while a video clip in it has not been probed.
   */
  val duration: Duration?
    get() =
      if (playable.isEmpty()) {
        Duration.ZERO
      } else {
        compositionOf {
          playable.forEach { addClip(it.clip(open = false)) }
          extras.forEach { extra -> extra.track(open = false)?.let { addTrack(it) } }
        }.duration
      }

  /**
   * Appends a clip showing [content] and selects it.
   *
   * @param at Where in the order it lands, the end by default.
   */
  fun add(
    content: ClipContent,
    label: String,
    preset: SamplePreset? = null,
    at: Int = items.size,
  ): ClipEdit {
    val clip = ClipEdit(ClipId(nextId++), content, label, preset)
    items.add(at.coerceIn(0, items.size), clip)
    selected = clip.id
    return clip
  }

  /**
   * Adds an empty track that plays alongside the clips.
   */
  fun addTrack(label: String): TrackEdit = TrackEdit(TrackId(nextTrackId++), label).also { extras += it }

  /**
   * Appends a clip showing [content] to [track]. The selection stays where it was, since the clip tools edit
   * the clips of the edit itself.
   */
  fun addTo(
    track: TrackEdit,
    content: ClipContent,
    label: String,
    preset: SamplePreset? = null,
  ): ClipEdit = ClipEdit(ClipId(nextId++), content, label, preset).also { track.add(it) }

  /**
   * Drops the track named [id] and every clip on it.
   */
  fun removeTrack(id: TrackId) {
    extras.removeAll { it.id == id }
  }

  /**
   * Drops the clip named [id]. When it was the selected one, the clip that takes its place is selected, or the
   * last clip when it was at the end.
   */
  fun remove(id: ClipId) {
    val index = items.indexOfFirst { it.id == id }
    if (index < 0) return
    items.removeAt(index)
    if (selected == id) selected = (items.getOrNull(index) ?: items.lastOrNull())?.id
  }

  /**
   * Moves the clip named [id] so it plays at [to] in the order.
   */
  fun move(
    id: ClipId,
    to: Int,
  ) {
    val index = items.indexOfFirst { it.id == id }
    if (index < 0) return
    val target = to.coerceIn(0, items.lastIndex)
    if (target != index) items.add(target, items.removeAt(index))
  }

  /**
   * Selects the clip named [id], when the sequence holds it.
   */
  fun select(id: ClipId) {
    if (items.any { it.id == id }) selected = id
  }

  /**
   * Drops every clip and every track.
   */
  fun clear() {
    items.clear()
    extras.clear()
    selected = null
  }

  /**
   * Where each clip starts on the timeline, in the order they play. A clip that has not been probed takes no
   * room, so the ones after it start where it does until it has.
   */
  fun clipStarts(): List<Duration> =
    items.runningFold(Duration.ZERO) { start, clip -> start + (clip.duration ?: Duration.ZERO) }.dropLast(1)

  /**
   * Where the clip named [id] starts on the timeline, or null when the sequence does not hold it.
   */
  fun startOf(id: ClipId): Duration? = items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { clipStarts()[it] }

  /**
   * The clip playing at [time], or null when none is. A boundary belongs to the clip that starts there, and the
   * end of the sequence to its last clip.
   */
  fun clipAt(time: Duration): ClipEdit? {
    val starts = clipStarts()
    return items
      .withIndex()
      .lastOrNull { (index, clip) -> starts[index] <= time && (clip.duration ?: Duration.ZERO) > Duration.ZERO }
      ?.value
  }

  /**
   * Where [sourceTime] of the clip named [id] plays on the timeline, or null when the sequence does not hold it.
   * A source time the clip does not play is held at the nearer end of its run.
   */
  fun timeOf(
    id: ClipId,
    sourceTime: Duration,
  ): Duration? {
    val clip = items.firstOrNull { it.id == id } ?: return null
    return startOf(id)?.let { it + clip.runOffsetOf(sourceTime) }
  }

  /**
   * The source time of the clip named [id] that plays at [time] on the timeline, or null when the sequence does
   * not hold it. A time outside the clip's run is held at the nearer end of it.
   */
  fun sourceTimeOf(
    id: ClipId,
    time: Duration,
  ): Duration? {
    val clip = items.firstOrNull { it.id == id } ?: return null
    return startOf(id)?.let { clip.sourceTimeAt(time - it) }
  }

  /**
   * Puts every clip's and every track's knobs back where they started and keeps them.
   */
  fun reset() {
    items.forEach { it.reset() }
    extras.forEach { it.reset() }
  }
}
