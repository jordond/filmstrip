package dev.jordond.filmstrip.effects.overlay

import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import android.util.Pair as AndroidPair
import androidx.media3.effect.OverlayEffect as Media3OverlayEffect

/**
 * How many overlays one [Media3OverlayEffect] may carry.
 *
 * `OverlayShaderProgram` compiles a single GL program that samples the frame plus every overlay,
 * and a GL program has sixteen sampler units. Overlays beyond this belong in a second
 * [Media3OverlayEffect], which costs one more pass rather than failing: the shader program's constructor
 * refuses a longer list, and it is built on the GL thread when the input stream is registered, so a
 * violation surfaces mid-export rather than while planning.
 */
@InternalFilmstripApi
public const val MAX_OVERLAYS_PER_EFFECT: Int = 15

/**
 * Lowers a resolved placement onto media3's two-anchor overlay settings.
 *
 * Media3 rendezvous the overlay's anchor with the background's, and both are normalized device
 * coordinates rather than fractions: centre origin, `-1..1`, and +Y up where filmstrip authors +Y
 * down. Its scale is a multiplier on the overlay bitmap's own pixel size rather than a fraction of
 * the frame, so the drawn size is `bitmap * scale` and the multiplier is what turns one into the
 * other.
 *
 * The renderer brings the two anchors together itself, so the placement's pair crosses over as it
 * stands rather than through [OverlayPlacement.rectOn].
 *
 * @param bitmap The rasterized overlay's pixel size.
 * @param opacity Alpha applied to the whole overlay.
 */
internal fun OverlayPlacement.toOverlaySettings(
  bitmap: Size,
  opacity: Float,
): StaticOverlaySettings = AnimatedOverlaySettings().update(this, bitmap, opacity).fixed()

/**
 * The same settings with the overlay's alpha taken to zero, for the frames a timed overlay sits
 * outside.
 *
 * `OverlayShaderProgram.drawFrame` asks each overlay for its settings twice, once for the HDR
 * luminance multiplier and once for the matrix and the alpha, so handing back a settings object
 * that was already built is what keeps timing an overlay free.
 */
internal fun StaticOverlaySettings.hidden(): StaticOverlaySettings = fixed(alpha = 0f)

/**
 * One overlay's settings for the frame being drawn, rewritten in place each time it is asked for.
 *
 * `OverlayShaderProgram` reads the object inside `drawFrame` and keeps no reference to it, so a
 * single instance the overlay updates and hands back serves a whole export. Each pair is replaced
 * only when its numbers move, which leaves an overlay animating opacity alone allocating nothing
 * per frame.
 *
 * Mutating it in place is safe because the instance belongs to one overlay on one GL thread, and
 * both of `drawFrame`'s reads of a frame happen inside that one call, so nothing holds the values
 * across the next frame's rewrite.
 *
 * Its HDR luminance multiplier is the one [toOverlaySettings] writes, since that lowering runs
 * through [update] too, and the HDR branch reading the multiplier sees no difference between an
 * animated overlay and a still one.
 */
internal class AnimatedOverlaySettings : OverlaySettings {
  private var alpha: Float = OverlaySettings.DEFAULT_ALPHA_SCALE
  private var overlay: AndroidPair<Float, Float> = OverlaySettings.DEFAULT_OVERLAY_FRAME_ANCHOR
  private var background: AndroidPair<Float, Float> = OverlaySettings.DEFAULT_BACKGROUND_FRAME_ANCHOR
  private var scale: AndroidPair<Float, Float> = OverlaySettings.DEFAULT_SCALE

  override fun getAlphaScale(): Float = alpha

  override fun getOverlayFrameAnchor(): AndroidPair<Float, Float> = overlay

  override fun getBackgroundFrameAnchor(): AndroidPair<Float, Float> = background

  override fun getScale(): AndroidPair<Float, Float> = scale

  override fun getHdrLuminanceMultiplier(): Float = SDR_LUMINANCE

  /**
   * Rewrites these settings to draw [placement] at [opacity], and answers them.
   *
   * The one place a placement is turned into media3's numbers, which is why the settings built once
   * at resolve come through here as well.
   *
   * @param placement Where the overlay lands on the frame being drawn.
   * @param bitmap The rasterized overlay's pixel size.
   * @param opacity Alpha applied to the whole overlay.
   */
  fun update(
    placement: OverlayPlacement,
    bitmap: Size,
    opacity: Float,
  ): OverlaySettings {
    alpha = opacity.coerceAtLeast(0f)
    overlay = overlay.movedTo(placement.overlayAnchor.ndcX(), placement.overlayAnchor.ndcY())
    background = background.movedTo(placement.frameAnchor.ndcX(), placement.frameAnchor.ndcY())
    scale =
      scale.movedTo(
        placement.size.width.ratioTo(bitmap.width),
        placement.size.height.ratioTo(bitmap.height),
      )
    return this
  }
}

// Copies what any settings answer into an immutable set, at [alpha] rather than their own when one
// is named. The builder range-checks the anchors, which the numbers reaching it have already been
// held inside.
private fun OverlaySettings.fixed(alpha: Float = alphaScale): StaticOverlaySettings =
  StaticOverlaySettings
    .Builder()
    .setOverlayFrameAnchor(overlayFrameAnchor.first, overlayFrameAnchor.second)
    .setBackgroundFrameAnchor(backgroundFrameAnchor.first, backgroundFrameAnchor.second)
    .setScale(scale.first, scale.second)
    .setAlphaScale(alpha)
    .setHdrLuminanceMultiplier(hdrLuminanceMultiplier)
    .build()

// Both sides are unboxed before the comparison, or the boxing is the allocation the reuse was
// meant to save.
private fun AndroidPair<Float, Float>.movedTo(
  x: Float,
  y: Float,
): AndroidPair<Float, Float> {
  val heldX: Float = first
  val heldY: Float = second
  return if (heldX == x && heldY == y) this else AndroidPair(x, y)
}

// Media3 documents both anchors as @FloatRange(from = -1, to = 1) on StaticOverlaySettings.Builder,
// and implementing OverlaySettings directly walks past that check rather than lifting the limit, so
// an anchor an animation carried off the frame is held at the edge here. It is media3's limit and
// no other backend's: the same slide keeps going on Apple and on ffmpeg.
private fun Anchor.ndcX(): Float = (2f * x - 1f).coerceIn(-1f, 1f)

private fun Anchor.ndcY(): Float = (1f - 2f * y).coerceIn(-1f, 1f)

private fun Int.ratioTo(native: Int): Float = if (native <= 0) 1f else toFloat() / native

/**
 * Where SDR white sits in the pipeline's HDR working space.
 *
 * The working space normalises `1.0` to the HDR peak, which it takes as twice the SDR white point,
 * so an overlay left at media3's default of `1f` is drawn at peak brightness and glows against the
 * frame. Everything filmstrip composites is authored in SDR, so it is scaled to the SDR range and
 * adds no luminance of its own. Ignored on an SDR pipeline, which never reads the uniform.
 */
private const val SDR_LUMINANCE = 0.5f
