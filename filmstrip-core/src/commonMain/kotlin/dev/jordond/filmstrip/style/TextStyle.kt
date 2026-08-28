package dev.jordond.filmstrip.style

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable

/**
 * How burned-in text is drawn.
 *
 * Sizes are fractions of the output frame rather than points, so the same style lands identically
 * in a preview and in an export of any resolution.
 *
 * @property fontSize Cap height as a fraction of the output frame's height, in `0f..1f`.
 * @property resolvedFontFamily The font family to draw with, or null for the platform default.
 *   Filled in when the composition is built and used as-is at export.
 * @property weight How heavy the typeface is.
 * @property color Packed ARGB, as `0xAARRGGBB`.
 * @property backgroundColor Packed ARGB for a plate drawn behind the text, or null for none.
 * @property alignment How lines are aligned within the text block.
 * @property maxWidth Maximum line width as a fraction of the frame, in `0f..1f`. Text wraps at this
 *   width.
 */
@Serializable
@Poko
public class TextStyle(
  public val fontSize: Float = 0.06f,
  public val resolvedFontFamily: String? = null,
  public val weight: FontWeight = FontWeight.Regular,
  public val color: Int = WHITE,
  public val backgroundColor: Int? = null,
  public val alignment: TextAlignment = TextAlignment.Center,
  public val maxWidth: Float = 0.9f,
) {
  public companion object {
    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * White, centred, six percent of frame height.
     */
    public val Default: TextStyle = TextStyle()
  }
}

/**
 * How heavy the typeface is.
 */
@Serializable
public enum class FontWeight {
  Regular,
  Medium,
  Bold,
}

/**
 * How lines are aligned within the text block.
 */
@Serializable
public enum class TextAlignment {
  Start,
  Center,
  End,
}
