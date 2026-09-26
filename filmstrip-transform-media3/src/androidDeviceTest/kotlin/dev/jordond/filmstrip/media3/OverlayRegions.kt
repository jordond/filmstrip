package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.effects.overlay.OverlayPlacement
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size

// The corners and the middle of the frame, each well clear of the others.
internal val TOP_START = Region(0f, 0f, 0.3f, 0.3f)
internal val TOP_END = Region(0.7f, 0f, 1f, 0.3f)
internal val BOTTOM_START = Region(0f, 0.7f, 0.3f, 1f)
internal val BOTTOM_END = Region(0.7f, 0.7f, 1f, 1f)
internal val MIDDLE = Region(0.35f, 0.4f, 0.65f, 0.6f)

// The badge's own size in pixels, which the shared geometry places on a frame.
internal val BADGE = Size(BADGE_PX, BADGE_PX)

internal const val SPAN_TOLERANCE = 0.06f
internal const val ALPHA_TOLERANCE = 0.12f

private const val EDGE_INSET = 0.25f

/**
 * A patch well inside this placement on [frame], clear of the edges chroma subsampling smears.
 */
internal fun OverlayPlacement.core(frame: Size): Region {
  val rect = rectOn(frame)
  val insetX = rect.width * EDGE_INSET
  val insetY = rect.height * EDGE_INSET
  return Region(rect.left + insetX, rect.top + insetY, rect.right - insetX, rect.bottom - insetY)
}

internal fun Bitmap.frameSize() = Size(width, height)

internal val NormalizedRect.midX: Float get() = (left + right) / 2f

internal val NormalizedRect.midY: Float get() = (top + bottom) / 2f
