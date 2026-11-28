package dev.jordond.filmstrip.sample.stories

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.compose.overlay.ComposedOverlayState
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.media.MediaSource
import kotlin.math.hypot

/**
 * The tool laid over the picture, one at a time.
 */
internal enum class StoryTool { None, Text, Stickers, Draw, Export }

/**
 * A caption being written in text mode, kept apart from the story until Done.
 *
 * @property id The placed caption being edited, or null for a new one.
 * @property caption The caption as it stands.
 * @property scale The size the caption is placed at, set by text mode's slider.
 */
internal data class TextDraft(
  val id: Int?,
  val caption: StoryContent.Caption,
  val scale: Float = 1f,
)

/**
 * One editing session: the story, the clip under it, the open tool and what a gesture in flight is doing.
 *
 * Both shells drive this and nothing else, so what a finger does on a phone a mouse does on a desktop.
 */
@Stable
internal class StoryEditor {
  val story: StoryState = StoryState()

  /**
   * The elements the stage draws, which is what an export bakes. The stage records into it while it is in
   * composition.
   */
  val overlays: ComposedOverlayState = ComposedOverlayState()

  var source: MediaSource? by mutableStateOf(null)
    private set

  /**
   * Whether [source] came from the file picker, which holds something to free once the clip is let go.
   */
  var sourcePicked: Boolean = false
    private set

  /**
   * Opens [source] as the story's clip, or goes back to the clip picker when it is null.
   *
   * @param picked Whether the file picker handed the source over.
   */
  fun open(
    source: MediaSource?,
    picked: Boolean = false,
  ) {
    sourcePicked = picked
    this.source = source
  }

  var tool: StoryTool by mutableStateOf(StoryTool.None)

  /**
   * Whether a finger or a mouse holds an element. The chrome hides and the bin shows while it does.
   */
  var gesturing: Boolean by mutableStateOf(false)
    private set

  /**
   * The guides to draw for the held element, or null outside a gesture.
   */
  var guides: Snap? by mutableStateOf(null)
    private set

  /**
   * Whether the held element sits over the bin, so letting go deletes it.
   */
  var overBin: Boolean by mutableStateOf(false)
    private set

  /**
   * The caption text mode is writing, or null outside text mode.
   */
  var draft: TextDraft? by mutableStateOf(null)

  var brush: StoryBrush by mutableStateOf(StoryBrush.Pen)

  var strokeColor: Int by mutableStateOf(StoryContent.WHITE)

  var strokeWidthDp: Float by mutableStateOf(StoryStroke.DEFAULT_WIDTH_DP)

  /**
   * The stroke under the finger, drawn with the rest but not yet part of the story.
   */
  var pendingStroke: StoryStroke? by mutableStateOf(null)
    private set

  /**
   * How many strokes this visit to draw mode has added, which is how many its Undo can take back.
   */
  var drawnStrokes: Int by mutableStateOf(0)
    private set

  private var pressedSelected = false
  private var heldMoved = false
  private var heldFrom: Anchor? = null

  /**
   * Selects the element [id] as a press lands on it. A press on the element already selected can end as a tap.
   */
  fun pressed(id: Int) {
    pressedSelected = story.selectedId == id
    story.select(id)
  }

  /**
   * Opens text mode on the caption [id], or on a new caption when there is none.
   */
  fun openText(id: Int? = null) {
    val element = story.elements.firstOrNull { it.id == id }
    val caption = element?.content as? StoryContent.Caption
    draft =
      if (caption ==
        null
      ) {
        TextDraft(null, StoryContent.Caption(""))
      } else {
        TextDraft(id, caption, element.transform.scale)
      }
    tool = StoryTool.Text
  }

  /**
   * Changes the caption text mode is writing.
   */
  fun editDraft(change: (StoryContent.Caption) -> StoryContent.Caption) {
    draft = draft?.let { it.copy(caption = change(it.caption)) }
  }

  /**
   * Leaves text mode, placing the caption. A new one lands at the centre, an edited one stays where it was, and one
   * left blank is dropped.
   */
  fun closeText() {
    val written = draft ?: return
    draft = null
    tool = StoryTool.None
    val id = written.id
    val before = story.elements.firstOrNull { it.id == id }
    when {
      written.caption.text.isBlank() -> {
        id?.let(story::remove)
      }
      id == null -> {
        story.add(written.caption, OverlayTransform(scale = written.scale))
      }
      before?.content == written.caption && before.transform.scale == written.scale -> {
        Unit
      }
      else -> {
        story.update(id) {
          val placed = it.transform
          it.copy(
            content = written.caption,
            transform = OverlayTransform(placed.center, written.scale, placed.rotationDegrees),
          )
        }
      }
    }
  }

  fun openDraw() {
    story.select(null)
    drawnStrokes = 0
    tool = StoryTool.Draw
  }

  fun closeDraw() {
    pendingStroke = null
    tool = StoryTool.None
  }

  /**
   * Opens the share sheet, leaving text or draw mode first. The caption being written goes back on the stage and the
   * stroke under the finger comes off it, so the stage shows what the export bakes.
   */
  fun openExport() {
    closeText()
    closeDraw()
    tool = StoryTool.Export
  }

  /**
   * Starts a stroke at [point], a fraction of the frame, with the brush, colour and width picked.
   */
  fun strokeStarted(point: Anchor) {
    pendingStroke = StoryStroke(listOf(point), brush, strokeColor, strokeWidthDp)
  }

  fun strokeMoved(point: Anchor) {
    pendingStroke = pendingStroke?.let { it.copy(points = it.points + point) }
  }

  /**
   * Adds the stroke under the finger to the story as one undo step.
   */
  fun strokeEnded() {
    val stroke = pendingStroke ?: return
    pendingStroke = null
    story.addStroke(stroke)
    drawnStrokes++
  }

  /**
   * Takes back the last stroke this visit to draw mode added.
   */
  fun undoStroke() {
    if (drawnStrokes == 0) return
    drawnStrokes--
    story.undo()
  }

  /**
   * Moves the element [id] to [transform] after snapping it. Every input lands here, so they all snap alike.
   */
  fun moved(
    id: Int,
    transform: OverlayTransform,
  ) {
    val snap = snapped(transform)
    story.transform(id, snap.transform)
    if (gesturing) {
      heldMoved = true
      guides = snap
      // A handle sizes and turns an element where it sits, so only a gesture that carried it can drop it in the bin.
      overBin = snap.transform.center != heldFrom && isOverBin(snap.transform.center)
    }
  }

  /**
   * Opens or closes a gesture on the element [id]. One that ends over the bin deletes the element.
   */
  fun gesture(
    id: Int,
    active: Boolean,
  ) {
    gesturing = active
    story.gesture(active, discard = id.takeIf { !active && overBin })
    if (active) {
      heldMoved = false
      heldFrom =
        story.elements
          .firstOrNull { it.id == id }
          ?.transform
          ?.center
      return
    }
    val tapped = pressedSelected && !heldMoved
    pressedSelected = false
    guides = null
    overBin = false
    if (tapped) tapped(id)
  }

  private fun tapped(id: Int) {
    when (val content = story.elements.firstOrNull { it.id == id }?.content) {
      is StoryContent.Caption -> openText(id)
      is StoryContent.Sticker -> if (content.kind.styles > 1) story.update(id) { it.copy(content = content.stepped()) }
      null -> Unit
    }
  }
}

/**
 * Whether an element centred on [center] sits over the bin, its centre within [BIN_RADIUS] of [BIN_CENTER] as the
 * stage draws them.
 */
internal fun isOverBin(center: Anchor): Boolean =
  hypot(center.x - BIN_CENTER.x, (center.y - BIN_CENTER.y) / STORY_ASPECT.value) <= BIN_RADIUS

/**
 * Where the bin's centre sits on the frame.
 */
internal val BIN_CENTER: Anchor = Anchor(0.5f, 0.9f)

/**
 * The bin's radius, as a fraction of the frame's width.
 */
internal const val BIN_RADIUS: Float = 0.08f
