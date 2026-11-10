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
 * Uniform values are plain floats, and a pass reads a table through [WebGlStep.textures], which holds its bytes and
 * leaves the GL texture to the compositor, so a resolver can build a pass with no GL context.
 *
 * @property programKey Which shader programs draw this pass. The compositor links one program per step of a key that
 * carries [steps] and reuses it. A key with no [steps] has to name what the compositor folds into its own draw, through
 * the texture or color matrix uniform or [textureMatrixAt], and the compositor refuses any other.
 * @property uniforms Uniform name to value, in the layout the program declares.
 * @property textureMatrixAt The texture matrix this pass samples through at a composition time, as the nine floats of a
 * column-major `mat3`, or null when the pass is the same on every frame. The compositor evaluates it once per frame and
 * multiplies it into the chain where the pass sits.
 * @property steps The draws this pass runs the frame through, in order, and empty when the compositor folds the pass
 * into its own draw.
 */
public class WebGlPass private constructor(
  public val programKey: String,
  public val uniforms: Map<String, FloatArray>,
  @property:InternalFilmstripApi
  public val textureMatrixAt: ((compositionTime: Duration) -> FloatArray)?,
  @property:InternalFilmstripApi
  public val steps: List<WebGlStep>,
) {
  /**
   * A pass that samples through the same uniforms on every frame.
   *
   * Every built-in effect except a pan builds its pass this way.
   */
  public constructor(
    programKey: String,
    uniforms: Map<String, FloatArray>,
  ) : this(programKey, uniforms, null, emptyList())

  /**
   * A pass whose texture matrix moves with composition time.
   *
   * It carries no uniforms, so a fold over the uniforms of a chain reads nothing from it.
   */
  @InternalFilmstripApi
  public constructor(
    programKey: String,
    textureMatrixAt: (compositionTime: Duration) -> FloatArray,
  ) : this(programKey, emptyMap(), textureMatrixAt, emptyList())

  /**
   * A pass that draws the whole frame through [steps], on a target of its own, at the spatial or composite stage.
   *
   * It carries no uniforms, so a fold over the uniforms of a chain reads nothing from it.
   *
   * @throws IllegalArgumentException when [steps] is empty, two steps share a name, a step reads anything but
   * [WebGlStep.INPUT] and the steps ahead of it, or a sampler is named by both a step's reads and its textures.
   */
  @InternalFilmstripApi
  public constructor(programKey: String, steps: List<WebGlStep>) : this(programKey, emptyMap(), null, steps) {
    require(steps.isNotEmpty()) { "Program $programKey needs at least one step." }
    val readable = mutableSetOf(WebGlStep.INPUT)
    steps.forEach { step ->
      step.reads.values.forEach { source ->
        require(source in readable) { "Step ${step.name} of $programKey reads $source, which nothing wrote before it." }
      }
      step.textures.keys.forEach { sampler ->
        require(sampler !in step.reads) { "Step ${step.name} of $programKey binds $sampler twice." }
      }
      require(readable.add(step.name)) { "Program $programKey has more than one step named ${step.name}." }
    }
  }

  override fun toString(): String = "WebGlPass(programKey=$programKey, uniforms=${uniforms.keys})"
}

/**
 * One GLSL ES 3.00 fragment program in a [WebGlPass], with what it reads and what it needs on each frame.
 *
 * The fragment reads its textures at the `vUv` varying, whose `v` runs up the picture. Every frame it reads holds
 * straight color, not color premultiplied by alpha, and it writes straight color too. Its alpha is written as is, with
 * no blending, and clear pixels hold no fill until the compositor lays the result over one. The last step of a pass
 * writes the frame the pass was asked for, and every step before it writes a target of its own, which lives until the
 * pass ends and filters linearly when read.
 *
 * @property name What a later step calls this step's output in [reads].
 * @property fragmentSource The fragment shader source.
 * @property reads Each sampler this step reads, by name, to the [name] of a step ahead of it or [INPUT].
 * @property targetAt The size this step draws at, in pixels, for a frame of a width and height in pixels.
 * @property textures Each sampler this step reads a table through, by name, and the compositor uploads each once.
 * @property texturesAt Each sampler this step reads a table through that changes with the frame, by name, for a frame
 * at a composition time and a width and height in pixels. It names the same samplers on every frame, none of them in
 * [reads] or [textures], and a frame that names one of those fails by name. The compositor uploads each table once and
 * keeps the ones read most recently, so answering the same instance for frames that share a table costs no upload.
 * @property uniformsAt The uniforms for a frame at a composition time and a width and height in pixels, by name. A name
 * ending in `[0]` sets a float array from that element. Any other value is a float, a `vec2`, a `vec3`, a `vec4`, a
 * `mat3` or a `mat4` by its length, and any other length is refused.
 */
@InternalFilmstripApi
public class WebGlStep(
  public val name: String,
  public val fragmentSource: String,
  public val reads: Map<String, String>,
  public val targetAt: (width: Int, height: Int) -> Size = { width, height -> Size(width, height) },
  public val textures: Map<String, WebGlTable> = emptyMap(),
  public val texturesAt: (compositionTime: Duration, width: Int, height: Int) -> Map<String, WebGlTable> =
    { _, _, _ -> emptyMap() },
  public val uniformsAt: (compositionTime: Duration, width: Int, height: Int) -> Map<String, FloatArray>,
) {
  public companion object {
    /**
     * What [reads] names the frame the pass starts from.
     */
    public const val INPUT: String = "input"
  }
}

/**
 * A table of RGBA8 texels a [WebGlStep] reads with nearest fetches, which holds no GL state.
 *
 * Tables with the same bytes are equal and the compositor uploads them once. [hashCode] is worked out once, so looking
 * one up on every frame does not walk its bytes. Bytes go up as they are, with no premultiply or flip, so row 0 is the
 * row a fetch at `y = 0` reads.
 *
 * @property width The width in texels.
 * @property height The height in texels.
 * @property bytes Four bytes a texel.
 * @throws IllegalArgumentException when [bytes] does not hold four bytes for every texel.
 */
@InternalFilmstripApi
public class WebGlTable(
  public val width: Int,
  public val height: Int,
  public val bytes: ByteArray,
) {
  private val contentHash: Int = bytes.contentHashCode()

  init {
    require(width > 0 && height > 0 && bytes.size.toLong() == width.toLong() * height * BYTES_PER_TEXEL) {
      "A ${width}x$height table holds $BYTES_PER_TEXEL bytes a texel, and this one has ${bytes.size}."
    }
  }

  override fun equals(other: Any?): Boolean =
    this === other ||
      (
        other is WebGlTable &&
          width == other.width &&
          height == other.height &&
          contentHash == other.contentHash &&
          bytes.contentEquals(other.bytes)
      )

  override fun hashCode(): Int = contentHash

  override fun toString(): String = "WebGlTable(${width}x$height)"
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
private const val BYTES_PER_TEXEL = 4
