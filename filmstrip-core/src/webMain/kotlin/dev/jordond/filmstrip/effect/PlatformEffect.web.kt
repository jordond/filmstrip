@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureOrientation
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
 * @property textureMatrixAt The texture matrix this pass samples through at a composition time, as the nine floats of a
 * column-major `mat3`, or null when the pass is the same on every frame. The compositor evaluates it once per frame and
 * multiplies it into the chain where the pass sits.
 */
public class WebGlPass private constructor(
  public val programKey: String,
  public val uniforms: Map<String, FloatArray>,
  @property:InternalFilmstripApi
  public val textureMatrixAt: ((compositionTime: Duration) -> FloatArray)?,
) {
  /**
   * A pass that samples through the same uniforms on every frame.
   *
   * Every built-in effect except a pan builds its pass this way.
   */
  public constructor(programKey: String, uniforms: Map<String, FloatArray>) : this(programKey, uniforms, null)

  /**
   * A pass whose texture matrix moves with composition time.
   *
   * It carries no uniforms, so a fold over the uniforms of a chain reads nothing from it.
   */
  @InternalFilmstripApi
  public constructor(
    programKey: String,
    textureMatrixAt: (compositionTime: Duration) -> FloatArray,
  ) : this(programKey, emptyMap(), textureMatrixAt)

  override fun toString(): String = "WebGlPass(programKey=$programKey, uniforms=${uniforms.keys})"
}

/**
 * A raster already drawn at resolve, which the compositor uploads once and draws on every frame
 * [drawAt] answers for.
 *
 * The resolver works out where and how strongly it lands, so a compositor reads a rectangle and an
 * opacity and computes neither.
 *
 * @property source What the raster is uploaded from, a decoded image or samples alone.
 * @property size The pixel size the raster is uploaded at. A raster carrying samples takes their size, in the order
 * they are stored, and any other raster its own.
 * @property drawAt Where to draw the raster at a composition time, or null when it is not drawn on that frame.
 * @property color How a picture describes the color of its stored codes or samples, or null when the raster is
 * untagged and they are sRGB.
 * @property orientation The turn the compositor lays the uploaded raster out by when it draws it, which [drawAt]'s
 * rect already holds the shown picture's size in. A raster uploaded from samples is laid out already and takes
 * [PictureOrientation.Normal].
 */
@InternalFilmstripApi
public class WebGlOverlay(
  public val source: OverlaySource,
  public val size: Size,
  public val drawAt: (compositionTime: Duration) -> WebGlOverlayDraw?,
  public val color: PictureColor? = null,
  public val orientation: PictureOrientation = PictureOrientation.Normal,
) {
  override fun toString(): String = "WebGlOverlay($size)"
}

/**
 * What a [WebGlOverlay] is uploaded from.
 *
 * Samples are sixteen-bit RGBA, four a texel as [dev.jordond.filmstrip.picture.png.pngSamples] reads a PNG's. A PNG
 * carries its stored samples at their full depth, an AVIF the color core's YCbCr evaluator turns its samples into,
 * and text the straight color laid over its plate. The compositor converts samples wherever a raster carries them, in
 * place of an image's eight-bit codes.
 */
@InternalFilmstripApi
public sealed interface OverlaySource {
  /**
   * A decoded picture, uploaded from [samples] when it carries them and from [image] otherwise.
   *
   * @property image An `ImageBitmap` or an `OffscreenCanvas`, premultiplied on upload, which holds the picture's
   * stored codes unconverted.
   * @property samples The picture's samples, or null when it has none deeper than [image]'s codes.
   */
  public class Image(
    public val image: JsAny,
    public val samples: ShortArray? = null,
  ) : OverlaySource

  /**
   * A raster drawn straight into [samples], with no image behind it.
   *
   * @property samples The raster's samples, which it is uploaded from whole.
   */
  public class Samples(
    public val samples: ShortArray,
  ) : OverlaySource
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
