package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.sample.visibleWindow

/**
 * What a story element shows, a caption or a sticker.
 */
internal sealed interface StoryContent {
  data class Caption(
    val text: String,
    val face: StoryFace = StoryFace.Type,
    val color: Int = WHITE,
    val plate: StoryPlate = StoryPlate.None,
    val align: StoryAlign = StoryAlign.Center,
    val entrance: StoryEntrance = StoryEntrance.None,
  ) : StoryContent

  data class Sticker(
    val kind: StoryStickerKind,
    val style: Int = 0,
  ) : StoryContent

  companion object {
    const val WHITE: Int = 0xFFFFFFFF.toInt()

    /**
     * The colours a caption and a stroke can take, as ARGB.
     */
    val PALETTE: List<Int> =
      listOf(
        WHITE,
        0xFF000000.toInt(),
        0xFFFFB627.toInt(),
        0xFFFF4F3F.toInt(),
        0xFFFF7BA0.toInt(),
        0xFF38E1FF.toInt(),
        0xFF5BE37D.toInt(),
        0xFF8A6BFF.toInt(),
      )
  }
}

/**
 * The entry after this one, wrapping from the last to the first. A button that cycles a choice steps with it.
 */
internal inline fun <reified T : Enum<T>> T.next(): T = enumValues<T>().let { it[(ordinal + 1) % it.size] }

/**
 * The faces a caption can take.
 */
internal enum class StoryFace { Type, Display, Pixel, Script, Serif }

/**
 * What sits behind a caption's text. The plate button steps through these in order and wraps.
 */
internal enum class StoryPlate { None, Solid, Translucent }

/**
 * Which side a caption's lines sit against. The align button steps through these in order and wraps.
 */
internal enum class StoryAlign { Start, Center, End }

/**
 * One element on the story, where it sits and when it shows.
 *
 * The window is kept as the timing bar writes it, a switch and two times in seconds, and read through [window].
 */
internal data class StoryElement(
  val id: Int,
  val content: StoryContent,
  val transform: OverlayTransform = OverlayTransform(),
  val timed: Boolean = false,
  val startSeconds: Float = 0f,
  val endSeconds: Float = 0f,
) {
  /**
   * The time range the element shows for, or null when it shows for the whole story.
   */
  val window: TimeRange?
    get() = visibleWindow(timed, startSeconds, endSeconds)

  /**
   * How the element arrives, or null when it is drawn the same on every frame. Only a caption has an entrance.
   */
  val animation: OverlayAnimation?
    get() = (content as? StoryContent.Caption)?.entrance?.animation
}
