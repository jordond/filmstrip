package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Duration

/**
 * Drives one pick-to-export session against a [Filmstrip], exposing each step as compose state.
 *
 * The edit itself lives in [edit]. Everything here is the session around it: what was picked, what
 * the device said about it, and what came back from a run.
 */
@Stable
public class SampleAppState(
  internal val filmstrip: Filmstrip,
  public val recorder: DiagnosticsRecorder = DiagnosticsRecorder(),
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
  private val release: (MediaSink) -> Unit = ::releaseExport,
  private val download: suspend (SamplePreset) -> MediaSource = ::loadPreset,
) {
  public val edit: EditState = EditState()

  /**
   * What each registered backend calls itself, for the first line of a bug report.
   */
  public val backends: List<BackendInfo> get() = filmstrip.components.backends

  /**
   * Where the app is, newest last. Handed straight to `NavDisplay`.
   *
   * It lives on the state rather than in composition because the state outlives the activity, so a
   * rotation mid-export lands back on the screen the export was started from.
   */
  public val backStack: NavBackStack<SampleRoute> = NavBackStack(SampleRoute.Start)

  /**
   * The clips of the edit, in the order they play.
   */
  public val sequence: Sequence = Sequence()

  var pickFailure: String? by mutableStateOf(null)
    private set
  private val presets = PresetLoader(scope, recorder, download)
  val loadingPreset: SamplePreset? get() = presets.loading

  /**
   * The live preview of the edit and its transport.
   */
  public val preview: PreviewSession =
    PreviewSession(
      filmstrip = filmstrip,
      scope = scope,
      recorder = recorder,
      composition = ::previewComposition,
      spec = ::spec,
      duration = { editedDuration },
      frameRate = { options.frameRate ?: info?.video?.frameRate?.toInt() },
    )

  /**
   * The plan and the export of the edit.
   */
  public val exports: ExportSession =
    ExportSession(
      filmstrip = filmstrip,
      scope = scope,
      recorder = recorder,
      release = release,
      composition = ::composition,
      spec = ::spec,
      onStart = preview::pause,
      onSucceeded = {
        backStack.remove(SampleRoute.Export)
        navigateTo(SampleRoute.Result)
      },
    )

  private val prober = ClipProber(filmstrip, scope, recorder, ::onEditChanged)

  private var activeToolState: EditorTool by mutableStateOf(EditorTool.Clip)

  /**
   * What the timeline strip lays out. The trim handles are on the clip view only.
   */
  var timelineView: TimelineView by mutableStateOf(TimelineView.Clip)

  /**
   * Which tool panel is open, which decides what the tool panel shows.
   *
   * Reloads the live preview on the way in or out of [croppingRect], since that flips whether the
   * player renders the rectangle crop.
   */
  var activeTool: EditorTool
    get() = activeToolState
    set(value) {
      val wasCroppingRect = croppingRect
      val wasPanning = panEnd != null
      activeToolState = value
      panEndState = null
      if (croppingRect != wasCroppingRect || wasPanning) preview.reload()
    }

  private var panEndState: PanEnd? by mutableStateOf(null)

  /**
   * Which rectangle of the selected clip's pan is dragged on the stage, or null while the pan plays. The live
   * preview holds that clip's frame still under the rectangle, and leaving the clip or its tool puts the pan back.
   */
  var panEnd: PanEnd?
    get() = panEndState
    set(value) {
      val changed = (value == null) != (panEndState == null)
      panEndState = value
      if (changed) preview.reload()
    }

  /**
   * True while the crop tool is open on a rectangle crop, which is when the crop overlay is drawn
   * over the stage and the live preview renders the frame before that crop rather than after it.
   */
  val croppingRect: Boolean
    get() = activeTool == EditorTool.Crop && edit.cropMode == CropMode.Rect

  public val options: ExportOptions = ExportOptions()

  public val frames: FrameGrab = FrameGrab(filmstrip, scope, recorder)

  /**
   * What the device's encoders can do.
   */
  public val device: DeviceCapabilities = DeviceCapabilities(filmstrip, scope, recorder)

  /**
   * True while the device is still reading a clip.
   */
  val probing: Boolean
    get() = sequence.clips.any { it.probing }

  /**
   * What the device said about the clip the output frame follows, or null until it has.
   */
  val info: MediaInfo?
    get() = sequence.lead?.info

  /**
   * Why the edit cannot load, which is why its first clip could not while no clip can. Null while a clip can
   * or while nothing is picked.
   */
  val sourceFailure: String?
    get() = if (sequence.playable.isEmpty()) sequence.clips.firstNotNullOfOrNull { it.failure } else null

  /**
   * The name the editor shows for the edit, its clip's while there is one and a count past that.
   */
  val title: String
    get() = sequence.clips.singleOrNull()?.label ?: "${sequence.clips.size} clips"

  /**
   * True while the transport has something to run, which an edit that cannot load or a stopped preview does not.
   */
  val canPlay: Boolean
    get() = sequence.playable.isNotEmpty() && !probing && (preview.previewError == null || preview.previewUnavailable)

  val sourceAspect: Float
    get() {
      val shot =
        info
          ?.video
          ?.displaySize
          ?.aspect
          ?.takeIf { it > 0f } ?: DEFAULT_ASPECT
      return if (sequence.lead?.transform?.sideways == true) 1f / shot else shot
    }

  val editedDuration: Duration
    get() = sequence.duration ?: Duration.ZERO

  /**
   * How long the edit runs, in seconds.
   */
  val editedDurationSeconds: Float
    get() = editedDuration.inWholeMilliseconds / 1000f

  /**
   * Opens the editor on [source] alone, dropping whatever was being edited.
   */
  fun onPicked(
    source: MediaSource?,
    label: String,
    preset: SamplePreset? = null,
  ) {
    if (source == null) return

    presets.cancel()
    openSource(source, label, preset)
  }

  private fun openSource(
    source: MediaSource,
    label: String,
    preset: SamplePreset?,
  ) {
    dropClips()
    pickFailure = null
    exports.reset()
    frames.clear()
    preview.stop()
    preview.positionSeconds = 0f
    edit.reset()
    activeTool = EditorTool.Clip
    timelineView = TimelineView.Clip
    addClip(source, label, preset)
    navigateTo(SampleRoute.Editor)
  }

  /**
   * Appends a clip over [source] to the edit and asks the device about it. A clip the device cannot read is
   * marked and left out of the edit, and the rest play on.
   */
  fun addClip(
    source: MediaSource,
    label: String,
    preset: SamplePreset? = null,
  ) {
    val clip = sequence.add(ClipContent.Video(source), label, preset)
    if (sequence.clips.size > 1) timelineView = TimelineView.Sequence
    pickFailure = null
    // The label is the user's own file name, so the log gets the same redaction the report does.
    recorder.record("source.picked", "source" to (preset?.fileName ?: source.redactedName()))

    prober.probe(clip, source)
  }

  /**
   * Appends [image] to the edit as a picture held on screen.
   */
  fun addImage(
    image: ImageSource,
    label: String,
  ) {
    added(sequence.add(ClipContent.Image(image), label), image.redactedName())
  }

  /**
   * Appends a flat colour held on screen to the edit.
   */
  fun addSolid() {
    added(sequence.add(ClipContent.Solid, "Colour"), "a colour")
  }

  private fun added(
    clip: ClipEdit,
    name: String,
  ) {
    if (sequence.clips.size > 1) timelineView = TimelineView.Sequence
    pickFailure = null
    panEndState = null
    recorder.record("clip.added", "clip" to "${clip.id.value}", "source" to name)
    onEditChanged()
  }

  /**
   * Downloads [preset] and opens the editor on it, reporting through [pickFailure] if the download
   * fails. Closing the project or picking another clip drops the download.
   */
  fun pickPreset(preset: SamplePreset) {
    loadPreset(preset) { source -> openSource(source, preset.name, preset) }
  }

  /**
   * Downloads [preset] and appends it to the edit, reporting through [pickFailure] if the download fails.
   */
  fun addPreset(preset: SamplePreset) {
    loadPreset(preset) { source -> addClip(source, preset.name, preset) }
  }

  private fun loadPreset(
    preset: SamplePreset,
    onLoaded: (MediaSource) -> Unit,
  ) {
    if (presets.loading == null) pickFailure = null
    presets.load(preset, onFailed = { pickFailure = it }, onLoaded = onLoaded)
  }

  /**
   * Lays [source] under the edit as a new audio track and asks the device about it.
   */
  fun addTrack(
    source: MediaSource,
    label: String,
    looping: Boolean = false,
    preset: SamplePreset? = null,
  ) {
    val track = sequence.addTrack(label)
    track.looping = looping
    addToTrack(track, source, label, preset)
  }

  /**
   * Appends [source] to [track] and asks the device about it.
   */
  fun addToTrack(
    track: TrackEdit,
    source: MediaSource,
    label: String,
    preset: SamplePreset? = null,
  ) {
    pickFailure = null
    recorder.record("track.picked", "source" to (preset?.fileName ?: source.redactedName()))
    prober.probe(sequence.addTo(track, ClipContent.Video(source), label, preset), source)
  }

  /**
   * Downloads [preset] and loops its audio under the edit, reporting through [pickFailure] if the download fails.
   */
  fun addMusic(preset: SamplePreset) {
    loadPreset(preset) { source -> addTrack(source, preset.name, looping = true, preset = preset) }
  }

  /**
   * Drops the track named [id] from the edit.
   */
  fun removeTrack(id: TrackId) {
    sequence.tracks
      .firstOrNull { it.id == id }
      ?.clips
      ?.forEach { prober.cancel(it.id) }
    sequence.removeTrack(id)
    onEditChanged()
  }

  /**
   * Drops the clip named [id] from the edit.
   */
  fun removeClip(id: ClipId) {
    prober.cancel(id)
    sequence.remove(id)
    panEndState = null
    onEditChanged()
  }

  /**
   * Moves the clip named [id] so it plays at [to] in the order.
   */
  fun moveClip(
    id: ClipId,
    to: Int,
  ) {
    sequence.move(id, to)
    onEditChanged()
  }

  /**
   * Selects the clip named [id], which is the one the clip tools then edit.
   */
  fun select(id: ClipId) {
    if (id != sequence.selected) panEnd = null
    sequence.select(id)
  }

  /**
   * Selects the clip named [id] and brings the playhead to its start, unless the playhead is already on it.
   */
  fun focusClip(id: ClipId) {
    select(id)
    val start = sequence.startOf(id) ?: return
    if (sequence.clipAt(preview.positionSeconds.asSeconds())?.id != id) {
      preview.seekTo(start.inWholeMilliseconds / 1000f)
    }
  }

  private fun dropClips() {
    prober.cancelAll()
    sequence.clear()
  }

  /**
   * Records that the platform picker could not finish, which is not the same as the user declining.
   */
  fun onPickFailed(message: String?) {
    pickFailure = message ?: "No reason given."
    recorder.record("source.pickFailed", "reason" to pickFailure.orEmpty())
  }

  /**
   * Drops the plan and the export an edit just invalidated.
   */
  fun onEditChanged() {
    exports.reset()
    preview.positionSeconds = preview.positionSeconds.coerceIn(0f, editedDurationSeconds)
    preview.reload()
  }

  /**
   * Pushes a route, unless it is already the one on top.
   */
  fun navigateTo(route: SampleRoute) {
    if (backStack.lastOrNull() != route) backStack.add(route)
  }

  /**
   * Pops one route. Back from the editor closes the clip, and back from the root is left to the platform.
   */
  fun navigateBack() {
    if (backStack.lastOrNull() == SampleRoute.Editor) {
      closeProject()
      return
    }
    if (backStack.size <= 1) return
    backStack.removeLastOrNull()
  }

  fun openExport() {
    navigateTo(SampleRoute.Export)
  }

  fun openCapabilities() {
    navigateTo(SampleRoute.Capabilities)
    device.refresh()
  }

  fun openDiagnostics() {
    navigateTo(SampleRoute.Diagnostics)
  }

  /**
   * Drops the clip and everything derived from it, landing back on the picker.
   */
  fun closeProject() {
    preview.stop()
    frames.clear()
    exports.cancelExport()
    presets.cancel()
    dropClips()
    pickFailure = null
    exports.reset()
    backStack.clear()
    backStack.add(SampleRoute.Start)
  }

  fun composition(): Composition? = if (sequence.playable.isEmpty()) null else edit.composition(sequence)

  /**
   * The composition the live player renders.
   *
   * Omits the rectangle crop while [croppingRect] is true, since the overlay drawn over the player
   * authors that rectangle against the frame before the crop rather than after it. Holds the selected clip
   * still while [panEnd] is set, for the same reason.
   */
  private fun previewComposition(): Composition? =
    if (sequence.playable.isEmpty()) {
      null
    } else {
      edit.composition(sequence, cropped = !croppingRect, unpanned = sequence.selected.takeIf { panEnd != null })
    }

  fun spec(): ExportSpec = options.spec()

  /**
   * Saves the frame under the playhead, rendered from the edit the export runs.
   */
  fun saveFrame() {
    val composition = composition() ?: return
    frames.save(composition, preview.positionSeconds.asSeconds())
  }

  /**
   * How faithfully each effect in the current edit will preview, straight from the engine. An effect on a clip
   * counts as much as one on the whole edit, and one used twice is listed once.
   */
  fun parity(): List<Pair<EffectId, String>> =
    composition()
      ?.allEffects()
      .orEmpty()
      .map { it.id }
      .distinct()
      .map { id -> id to (filmstrip.parityOf(id)?.name ?: "unknown") }

  private companion object {
    const val DEFAULT_ASPECT = 16f / 9f
  }
}
