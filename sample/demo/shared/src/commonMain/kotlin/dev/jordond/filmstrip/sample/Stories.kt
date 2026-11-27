package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayCapturer
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.log2
import kotlin.math.pow

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
 * The stickers the demo offers, each drawn in Compose as a shape or a small badge.
 */
internal enum class StorySticker { Heart, Star, Sparkle, Rec, Arrow }

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
   * The capturer the app's capture host draws for, bound while the host is in composition.
   */
  internal var capturer: OverlayCapturer? = null

  internal val canvas: OverlayCanvas = OverlayCanvas()

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

  /**
   * Bakes every element into an overlay on [output], drawing each with [content]. An element wholly off the frame is
   * left out.
   *
   * @throws IllegalStateException When there are elements to bake and no capture host is bound.
   */
  internal suspend fun bake(
    output: Size,
    content: @Composable (StoryElement) -> Unit,
  ): List<ImageOverlay> {
    val baking = elements
    if (baking.isEmpty()) return emptyList()
    val capturer = checkNotNull(capturer) { "Story elements need the capture host mounted to export." }
    return baking.mapNotNull { element ->
      capturer.capture(
        transform = element.transform,
        canvas = canvas,
        output = output,
        visibleDuring = visibleWindow(element.timed, element.startSeconds, element.endSeconds),
      ) { content(element) }
    }
  }

  private fun add(content: StoryContent) {
    val id = nextId++
    elements = elements + StoryElement(id, content)
    selectedId = id
    onChange()
  }
}

/**
 * Where [scale] sits on the demo's size slider, which counts doublings of the scale. A step of one doubles it and a
 * step of minus one halves it.
 */
internal fun sizeStepOf(scale: Float): Float =
  log2(scale.coerceIn(ComposedOverlayDefaults.MIN_SCALE, ComposedOverlayDefaults.MAX_SCALE))

/**
 * The scale the size slider at [step] asks for, held inside the bounds a pinch keeps to.
 */
internal fun scaleOfStep(step: Float): Float =
  2f.pow(step).coerceIn(ComposedOverlayDefaults.MIN_SCALE, ComposedOverlayDefaults.MAX_SCALE)

/**
 * The range of the size slider, from the smallest scale a pinch allows to the largest.
 */
internal val SIZE_STEPS: ClosedFloatingPointRange<Float> =
  log2(ComposedOverlayDefaults.MIN_SCALE)..log2(ComposedOverlayDefaults.MAX_SCALE)

/**
 * [degrees] as the same turn inside -180 to 180, the range of the demo's turn slider. A turn already inside it comes
 * back unchanged.
 */
internal fun turnOf(degrees: Float): Float {
  if (degrees in -HALF_TURN..HALF_TURN) return degrees
  val wrapped = degrees.mod(FULL_TURN)
  return if (wrapped > HALF_TURN) wrapped - FULL_TURN else wrapped
}

private const val HALF_TURN = 180f
private const val FULL_TURN = 360f
