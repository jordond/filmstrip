package dev.jordond.filmstrip.effect

/**
 * The browser form: a declaration of one render pass, not a compiled program.
 *
 * A WebGL program needs a live context, costs milliseconds to link and cannot be shared between
 * contexts, so a resolver cannot hand back a realised program the way the Android and Apple ones
 * do. It hands back what to draw instead, and the pipeline compiles and caches by
 * [WebGlPass.programKey]. Media3 splits `GlEffect` from `toGlShaderProgram` for the same reason.
 *
 * @property pass What this effect draws.
 */
public actual class PlatformEffect(
  public val pass: WebGlPass,
)

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
