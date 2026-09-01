package dev.jordond.filmstrip.geometry

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable

/**
 * A size in whole pixels.
 *
 * Used for real frame dimensions: what a decoder reports, what an encoder accepts. Anything a
 * caller authors is normalised instead. See [NormalizedRect].
 *
 * @property width Width in pixels.
 * @property height Height in pixels.
 */
@Serializable
@Poko
@Immutable
public class Size(
  public val width: Int,
  public val height: Int,
) {
  /**
   * Width over height, or zero when [height] is zero.
   */
  public val aspect: Float
    get() = if (height == 0) 0f else width.toFloat() / height.toFloat()
}

/**
 * An aspect ratio.
 *
 * The companion holds the common ones, and any other pair of sides is valid.
 *
 * @property width The width side of the ratio.
 * @property height The height side of the ratio.
 */
@Serializable
@Poko
@Immutable
public class AspectRatio(
  public val width: Int,
  public val height: Int,
) {
  /**
   * Width over height, as a float.
   */
  public val value: Float
    get() = width.toFloat() / height.toFloat()

  public companion object {
    /**
     * 9:16, for reels, stories and shorts.
     */
    public val Portrait: AspectRatio = AspectRatio(9, 16)

    /**
     * 1:1, square.
     */
    public val Square: AspectRatio = AspectRatio(1, 1)

    /**
     * 16:9, standard widescreen.
     */
    public val Landscape: AspectRatio = AspectRatio(16, 9)

    /**
     * 4:3, the classic television ratio.
     */
    public val Classic: AspectRatio = AspectRatio(4, 3)

    /**
     * 4:5, the Instagram feed ratio.
     */
    public val Feed: AspectRatio = AspectRatio(4, 5)

    /**
     * 2.39:1, anamorphic cinema.
     */
    public val Cinema: AspectRatio = AspectRatio(239, 100)
  }
}

/**
 * How content is fitted into a target frame.
 */
public enum class Fit {
  /**
   * Letterbox or pillarbox to fit inside the frame. The bars are written into the output.
   */
  Contain,

  /**
   * Fill the frame and crop the overflow. The default when reframing.
   */
  Crop,

  /**
   * Stretch to fill the frame, ignoring the source aspect ratio.
   */
  Stretch,
}

/**
 * A rectangle in `0f..1f` of the frame, origin top-left with +Y down, the convention Compose,
 * SwiftUI and CSS use.
 *
 * Crop coordinates address whatever geometry ran before them. Rotation runs before crop, so a rect
 * is expressed in the rotated frame's space rather than the source frame's.
 *
 * @property left Left edge as a fraction of the frame width.
 * @property top Top edge as a fraction of the frame height.
 * @property right Right edge as a fraction of the frame width.
 * @property bottom Bottom edge as a fraction of the frame height.
 */
@Serializable
@Poko
@Immutable
public class NormalizedRect(
  public val left: Float,
  public val top: Float,
  public val right: Float,
  public val bottom: Float,
) {
  /**
   * Width as a fraction of the frame.
   */
  public val width: Float get() = right - left

  /**
   * Height as a fraction of the frame.
   */
  public val height: Float get() = bottom - top

  /**
   * True when the rect has positive area and lies inside the frame.
   */
  public val isValid: Boolean
    get() =
      width > 0f && height > 0f &&
        left >= 0f && top >= 0f && right <= 1f && bottom <= 1f

  public companion object {
    /**
     * The whole frame, the identity crop.
     */
    public val Full: NormalizedRect = NormalizedRect(0f, 0f, 1f, 1f)
  }
}

/**
 * A point in `0f..1f` of the frame, origin top-left, with constants for the nine common positions.
 *
 * An anchor names a spot in the output frame, after every geometry effect has run.
 *
 * @property x Horizontal position as a fraction of the frame width.
 * @property y Vertical position as a fraction of the frame height.
 */
@Serializable
@Poko
@Immutable
public class Anchor(
  public val x: Float,
  public val y: Float,
) {
  public companion object {
    public val TopStart: Anchor = Anchor(0f, 0f)
    public val TopCenter: Anchor = Anchor(0.5f, 0f)
    public val TopEnd: Anchor = Anchor(1f, 0f)
    public val CenterStart: Anchor = Anchor(0f, 0.5f)
    public val Center: Anchor = Anchor(0.5f, 0.5f)
    public val CenterEnd: Anchor = Anchor(1f, 0.5f)
    public val BottomStart: Anchor = Anchor(0f, 1f)
    public val BottomCenter: Anchor = Anchor(0.5f, 1f)
    public val BottomEnd: Anchor = Anchor(1f, 1f)
  }
}

/**
 * One of the four corners of the output frame.
 */
@Serializable
public enum class Corner {
  TopStart,
  TopEnd,
  BottomStart,
  BottomEnd,
}

/**
 * Which axis to mirror across.
 */
@Serializable
public enum class FlipAxis {
  /**
   * Mirror left to right.
   */
  Horizontal,

  /**
   * Mirror top to bottom.
   */
  Vertical,
}
