package dev.jordond.filmstrip.effects.overlay

import androidx.media3.effect.StaticOverlaySettings
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
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
 * Media3 rendezvous the overlay's anchor with the background's, and both are normalised device
 * coordinates rather than fractions: centre origin, `-1..1`, and +Y up where filmstrip authors +Y
 * down. Its scale is a multiplier on the overlay bitmap's own pixel size rather than a fraction of
 * the frame, so the drawn size is `bitmap * scale` and the multiplier is what turns one into the
 * other.
 *
 * The renderer brings the two anchors together itself, so the placement's pair crosses over as it
 * stands rather than through [OverlayPlacement.rectOn].
 *
 * @param bitmap The rasterised overlay's pixel size.
 * @param opacity Alpha applied to the whole overlay.
 */
internal fun OverlayPlacement.toOverlaySettings(
  bitmap: Size,
  opacity: Float,
): StaticOverlaySettings =
  StaticOverlaySettings
    .Builder()
    .setOverlayFrameAnchor(overlayAnchor.ndcX(), overlayAnchor.ndcY())
    .setBackgroundFrameAnchor(frameAnchor.ndcX(), frameAnchor.ndcY())
    .setScale(size.width.ratioTo(bitmap.width), size.height.ratioTo(bitmap.height))
    .setAlphaScale(opacity.coerceAtLeast(0f))
    .setHdrLuminanceMultiplier(SDR_LUMINANCE)
    .build()

/**
 * The same settings with the overlay scaled out of sight, for the frames a timed overlay sits
 * outside of.
 *
 * `TextureOverlay` is asked for its settings once per frame, so switching between the two is the
 * supported way to time an overlay and costs nothing beyond the comparison.
 */
internal fun StaticOverlaySettings.hidden(): StaticOverlaySettings =
  StaticOverlaySettings
    .Builder()
    .setOverlayFrameAnchor(overlayFrameAnchor.first, overlayFrameAnchor.second)
    .setBackgroundFrameAnchor(backgroundFrameAnchor.first, backgroundFrameAnchor.second)
    .setScale(scale.first, scale.second)
    .setAlphaScale(0f)
    .setHdrLuminanceMultiplier(hdrLuminanceMultiplier)
    .build()

// Media3 range-checks both anchors and throws outside -1..1, so a margin wider than the frame is
// held at the edge rather than allowed to reach the builder.
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
