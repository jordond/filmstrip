package dev.jordond.filmstrip.sample.stories

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.compose.overlay.OverlayTransform

/**
 * Everything of a story that undo steps back through.
 */
internal data class StorySnapshot(
  val elements: List<StoryElement> = emptyList(),
  val strokes: List<StoryStroke> = emptyList(),
  val look: StoryLook = StoryLook.Original,
)

/**
 * The story being edited: its elements in draw order, the drawing, the look, the selection and the undo history.
 *
 * Every edit goes through here, so each one is an undo step. A gesture is one step however many moves it reports, and
 * so is a run of wheel or key steps on one element.
 */
@Stable
internal class StoryState {
  private var snapshot by mutableStateOf(StorySnapshot())
  private var undone by mutableStateOf(emptyList<StorySnapshot>())
  private var redone by mutableStateOf(emptyList<StorySnapshot>())
  private var gesturing = false
  private var stepping: Pair<String, Int>? = null
  private var nextId = 0

  /**
   * The elements from the bottom of the stack to the top.
   */
  val elements: List<StoryElement> get() = snapshot.elements

  val strokes: List<StoryStroke> get() = snapshot.strokes

  val look: StoryLook get() = snapshot.look

  var selectedId: Int? by mutableStateOf(null)
    private set

  val selected: StoryElement?
    get() = elements.firstOrNull { it.id == selectedId }

  val canUndo: Boolean get() = undone.isNotEmpty()

  val canRedo: Boolean get() = redone.isNotEmpty()

  /**
   * Adds an element showing [content] at [transform], the centre of the frame unless one is given, on top and
   * selected.
   *
   * @return The new element's id.
   */
  fun add(
    content: StoryContent,
    transform: OverlayTransform = OverlayTransform(),
  ): Int {
    val id = nextId++
    edit { it.copy(elements = it.elements + StoryElement(id, content, transform)) }
    selectedId = id
    return id
  }

  /**
   * Selects the element [id] and brings it to the top, or clears the selection when [id] is null. Reordering is not
   * an undo step.
   */
  fun select(id: Int?) {
    selectedId = id
    val picked = elements.firstOrNull { it.id == id } ?: return
    if (elements.last() != picked) snapshot = snapshot.copy(elements = elements - picked + picked)
  }

  /**
   * Opens or closes a gesture. Every [transform] between the two is one undo step, and a gesture that changed
   * nothing leaves none. A gesture closing with [discard] removes that element as part of the same step.
   */
  fun gesture(
    active: Boolean,
    discard: Int? = null,
  ) {
    stepping = null
    if (active == gesturing) return
    gesturing = active
    if (active) {
      remember()
      return
    }
    if (discard != null) {
      snapshot = snapshot.copy(elements = elements.filterNot { it.id == discard })
      if (selectedId == discard) selectedId = null
    }
    if (undone.lastOrNull() == snapshot) undone = undone.dropLast(1)
  }

  /**
   * Moves the element [id] to [transform]. Outside a gesture, a run of these on the same element is one undo step.
   */
  fun transform(
    id: Int,
    transform: OverlayTransform,
  ) {
    if (gesturing) {
      change(id) { it.copy(transform = transform) }
    } else {
      step(MOVING to id) { it.copy(transform = transform) }
    }
  }

  /**
   * Sets when the element [id] shows. A run of these on the same element is one undo step, so a handle dragged along
   * the timing bar undoes in one go.
   */
  fun window(
    id: Int,
    timed: Boolean,
    startSeconds: Float,
    endSeconds: Float,
  ) {
    step(TIMING to id) { it.copy(timed = timed, startSeconds = startSeconds, endSeconds = endSeconds) }
  }

  /**
   * Replaces the element [id] with what [change] makes of it.
   */
  fun update(
    id: Int,
    change: (StoryElement) -> StoryElement,
  ) {
    edit { it.copy(elements = it.elements.map { element -> if (element.id == id) change(element) else element }) }
  }

  fun remove(id: Int) {
    edit { it.copy(elements = it.elements.filterNot { element -> element.id == id }) }
    if (selectedId == id) selectedId = null
  }

  fun addStroke(stroke: StoryStroke) {
    edit { it.copy(strokes = it.strokes + stroke) }
  }

  fun look(look: StoryLook) {
    if (look != snapshot.look) edit { it.copy(look = look) }
  }

  fun undo() {
    val previous = undone.lastOrNull() ?: return
    undone = undone.dropLast(1)
    redone = redone + snapshot
    restore(previous)
  }

  fun redo() {
    val next = redone.lastOrNull() ?: return
    redone = redone.dropLast(1)
    undone = undone + snapshot
    restore(next)
  }

  private fun step(
    key: Pair<String, Int>,
    change: (StoryElement) -> StoryElement,
  ) {
    if (stepping != key) remember()
    change(key.second, change)
    stepping = key
  }

  private fun change(
    id: Int,
    change: (StoryElement) -> StoryElement,
  ) {
    snapshot = snapshot.copy(elements = elements.map { if (it.id == id) change(it) else it })
  }

  private fun edit(change: (StorySnapshot) -> StorySnapshot) {
    remember()
    stepping = null
    snapshot = change(snapshot)
  }

  private fun remember() {
    undone = (undone + snapshot).takeLast(HISTORY)
    redone = emptyList()
  }

  private fun restore(to: StorySnapshot) {
    stepping = null
    snapshot = to
    if (to.elements.none { it.id == selectedId }) selectedId = null
  }

  private companion object {
    const val HISTORY = 100
    const val MOVING = "moving"
    const val TIMING = "timing"
  }
}
