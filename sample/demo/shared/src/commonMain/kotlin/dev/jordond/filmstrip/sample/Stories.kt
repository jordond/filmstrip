package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.compose.overlay.ComposedOverlayState
import dev.jordond.filmstrip.compose.overlay.OverlayBaker
import dev.jordond.filmstrip.compose.overlay.OverlayTransform

/**
 * What a story element shows.
 */
internal sealed interface StoryContent {
  data class Caption(
    val text: String,
    val font: StoryFont = StoryFont.Type,
    val color: Int = EditState.WHITE,
    val plate: Int? = null,
  ) : StoryContent

  data class Sticker(
    val sticker: StorySticker,
  ) : StoryContent
}

/**
 * The faces a caption can take, each one of the fonts bundled with the demo's theme.
 */
internal enum class StoryFont { Display, Type, Pixel }

/**
 * One element on the story, where it sits and when it shows.
 */
internal data class StoryElement(
  val id: Int,
  val content: StoryContent,
  val transform: OverlayTransform = OverlayTransform(),
  val timed: Boolean = false,
  val startSeconds: Float = 0f,
  val endSeconds: Float = 0f,
)

/**
 * The text and stickers laid over the edit, edited live on the stage and baked into pictures only for an export.
 *
 * The preview composition never holds them, so moving one never re-plans the preview.
 */
@Stable
public class StoryOverlays {
  internal var elements: List<StoryElement> by mutableStateOf(emptyList())
    private set

  internal var selectedId: Int? by mutableStateOf(null)

  /**
   * The elements the stage draws, recorded while the stage is in composition.
   */
  internal val overlays: ComposedOverlayState = ComposedOverlayState()

  /**
   * Bakes every element the stage draws into an overlay on the frame it is given. With no elements nothing is asked
   * of the stage, so an edit without stories plans while no stage is on screen.
   */
  internal val baker: OverlayBaker =
    OverlayBaker { output -> if (elements.isEmpty()) emptyList() else overlays.bake(output) }

  /**
   * Runs after every change to the elements, which drops a plan baked from the old ones.
   */
  internal var onChange: () -> Unit = {}

  private var nextId = 0

  internal val selected: StoryElement?
    get() = elements.firstOrNull { it.id == selectedId }

  internal fun addCaption(text: String = "Your text") {
    add(StoryContent.Caption(text))
  }

  internal fun addSticker(sticker: StorySticker) {
    add(StoryContent.Sticker(sticker))
  }

  internal fun update(
    id: Int,
    change: (StoryElement) -> StoryElement,
  ) {
    elements = elements.map { if (it.id == id) change(it) else it }
    onChange()
  }

  internal fun remove(id: Int) {
    elements = elements.filterNot { it.id == id }
    if (selectedId == id) selectedId = null
    onChange()
  }

  internal fun clear() {
    elements = emptyList()
    selectedId = null
    onChange()
  }

  private fun add(content: StoryContent) {
    val id = nextId++
    elements = elements + StoryElement(id, content)
    selectedId = id
    onChange()
  }
}
