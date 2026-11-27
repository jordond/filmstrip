package dev.jordond.filmstrip.compose.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Where a composed overlay element lands on the output frame.
 *
 * @property box The axis-aligned box around the element once scaled and turned, as a fraction of the output frame. It
 * reaches outside `0f..1f` when the element hangs off an edge.
 * @property visible [box] clipped to the output frame.
 * @property raster The pixels of the output frame the element is drawn into. It is [visible] rounded out to whole
 * pixels with one pixel of padding a side for the antialiased edge, and held inside the frame.
 * @property center The centre of [raster], as a fraction of the output frame.
 */
@Poko
@Immutable
public class ElementBounds(
  public val box: NormalizedRect,
  public val visible: NormalizedRect,
  public val raster: IntRect,
  public val center: Anchor,
)

/**
 * Where content of [content] layout pixels lands on [output] under this transform.
 *
 * The content is drawn at [OverlayTransform.scale] times its canvas size, so one layout pixel covers
 * `scale * output.width / canvas width` output pixels. It is turned about its own centre, which lands on
 * [OverlayTransform.center]. The part off the frame is never drawn, so the raster stops at the frame's edge.
 *
 * @param content The element's laid out size, in layout pixels at [OVERLAY_LAYOUT_DENSITY].
 * @param canvas The canvas the content was laid out on.
 * @param output The output frame the element is drawn on, in pixels.
 * @return The element's bounds, or null when none of it falls on the frame.
 */
@OptIn(InternalFilmstripApi::class)
public fun OverlayTransform.boundsOn(
  content: IntSize,
  canvas: OverlayCanvas,
  output: Size,
): ElementBounds? {
  if (output.width <= 0 || output.height <= 0) return null
  val perLayoutPx = drawnScale(canvas, output.width.toFloat())
  val width = content.width * perLayoutPx
  val height = content.height * perLayoutPx
  val radians = rotationDegrees * PI / DEGREES_HALF_TURN
  val turnedWidth = (abs(width * cos(radians)) + abs(height * sin(radians))).toFloat()
  val turnedHeight = (abs(width * sin(radians)) + abs(height * cos(radians))).toFloat()
  val centreX = center.x * output.width
  val centreY = center.y * output.height

  val left = centreX - turnedWidth / 2
  val top = centreY - turnedHeight / 2
  val right = centreX + turnedWidth / 2
  val bottom = centreY + turnedHeight / 2
  val visibleLeft = left.coerceAtLeast(0f)
  val visibleTop = top.coerceAtLeast(0f)
  val visibleRight = right.coerceAtMost(output.width.toFloat())
  val visibleBottom = bottom.coerceAtMost(output.height.toFloat())
  if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return null

  val raster =
    IntRect(
      left = (floor(visibleLeft).toInt() - PADDING).coerceAtLeast(0),
      top = (floor(visibleTop).toInt() - PADDING).coerceAtLeast(0),
      right = (ceil(visibleRight).toInt() + PADDING).coerceAtMost(output.width),
      bottom = (ceil(visibleBottom).toInt() + PADDING).coerceAtMost(output.height),
    )
  return ElementBounds(
    box = NormalizedRect(left / output.width, top / output.height, right / output.width, bottom / output.height),
    visible =
      NormalizedRect(
        visibleLeft / output.width,
        visibleTop / output.height,
        visibleRight / output.width,
        visibleBottom / output.height,
      ),
    raster = raster,
    center =
      Anchor(
        (raster.left + raster.right) / 2f / output.width,
        (raster.top + raster.bottom) / 2f / output.height,
      ),
  )
}

private const val DEGREES_HALF_TURN = 180.0
private const val PADDING = 1
