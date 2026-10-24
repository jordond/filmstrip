@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PictureColour
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration

/**
 * The browser form: a declaration of one render pass, not a compiled program.
 *
 * A WebGL program needs a live context, costs milliseconds to link and cannot be shared between
 * contexts, so a resolver cannot hand back a realised program the way the Android and Apple ones
 * do. It hands back what to draw instead, and the pipeline compiles and caches by
 * [WebGlPass.programKey]. Media3 splits `GlEffect` from `toGlShaderProgram` for the same reason.
 *
 * @property pass What this effect draws.
 * @property overlay The raster this effect composites over the frame, or null when it is a pass
 *   and nothing more.
 */
public actual class PlatformEffect private constructor(
  public val pass: WebGlPass,
  @property:InternalFilmstripApi
  public val overlay: WebGlOverlay?,
) {
  public constructor(pass: WebGlPass) : this(pass, null)

  /**
   * An effect that composites [overlay] over the frame.
   *
   * Its [pass] carries no uniforms, so a fold over the passes of a chain reads nothing from it.
   */
  @InternalFilmstripApi
  public constructor(overlay: WebGlOverlay) : this(WebGlPass(OVERLAY_PROGRAM, emptyMap()), overlay)
}

/**
 * One pass of a browser render chain, described rather than compiled.
 *
 * Uniform values are plain floats because every v1 effect that reaches WebGL is a matrix, a
 * rectangle or a scalar. A pass needing a texture uniform belongs to the pipeline that owns the
 * texture, not to a declaration a resolver can build without a GL context.
 *
 * @property programKey Which shader program draws this pass. The pipeline links one program per
 *   distinct key and reuses it.
 * @property uniforms Uniform name to value, in the layout the program declares.
 */
public class WebGlPass(
  public val programKey: String,
  public val uniforms: Map<String, FloatArray>,
) {
  override fun toString(): String = "WebGlPass(programKey=$programKey, uniforms=${uniforms.keys})"
}

/**
 * A raster already drawn at resolve, which the compositor uploads once and draws on every frame
 * [drawAt] answers for.
 *
 * The resolver works out where and how strongly it lands, so a compositor reads a rectangle and an
 * opacity and computes neither.
 *
 * @property image An `ImageBitmap` or an `OffscreenCanvas`, premultiplied on upload.
 * @property size The pixel size the raster is uploaded at. A raster carrying [samples] takes their size, in the order
 * they are stored, and any other raster its own.
 * @property drawAt Where to draw the raster at a composition time, or null when it is not drawn on that frame.
 * @property colour How a picture describes the colour of its stored codes, which [image] holds unconverted, or null
 * when the raster is untagged and its codes are sRGB.
 * @property samples Sixteen-bit RGBA samples, four a texel as [dev.jordond.filmstrip.internal.pngSamples] reads a
 * PNG's, or null for any other raster. A PNG carries its stored samples at their full depth, and an AVIF the colour
 * core's YCbCr evaluator turns its samples into. The compositor converts these in place of [image]'s eight-bit codes.
 */
@InternalFilmstripApi
public class WebGlOverlay(
  public val image: JsAny,
  public val size: Size,
  public val drawAt: (compositionTime: Duration) -> WebGlOverlayDraw?,
  public val colour: PictureColour? = null,
  public val samples: ShortArray? = null,
) {
  override fun toString(): String = "WebGlOverlay($size)"
}

/**
 * How one overlay is drawn on one frame.
 *
 * @property rect The rectangle the raster covers, as a fraction of the frame it is drawn on. It may
 *   reach outside `0f..1f` when the overlay hangs over an edge.
 * @property opacity The alpha applied to the whole raster, in `0f..1f`.
 */
@InternalFilmstripApi
public class WebGlOverlayDraw(
  public val rect: NormalizedRect,
  public val opacity: Float,
) {
  override fun toString(): String = "WebGlOverlayDraw($rect, opacity=$opacity)"
}

private const val OVERLAY_PROGRAM = "filmstrip.overlay"
