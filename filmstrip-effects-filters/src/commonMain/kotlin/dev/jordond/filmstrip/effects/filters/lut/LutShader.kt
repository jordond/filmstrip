package dev.jordond.filmstrip.effects.filters.lut

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The GLSL ES 3.00 fragment source that draws a [Lut], read by media3 and the browser.
 *
 * It reads the [LutAtlas] with nearest fetches and blends the eight corners itself, the same trilinear as [CubeLut.at],
 * so nothing rests on a device's texture filtering. The table reads a pixel's straight colour and the pixel keeps its
 * alpha, which keeps a clear bar clear.
 */
@InternalFilmstripApi
public object LutShader {
  /**
   * The sampler the frame is bound to.
   */
  public const val TEXTURE: String = "uTexture"

  /**
   * The sampler the [LutAtlas] is bound to, which only takes nearest fetches.
   */
  public const val TABLE: String = "uLutTable"

  /**
   * The fragment source.
   */
  public val FRAGMENT: String by lazy { fragmentSource() }

  /**
   * The uniforms for [atlas], by name, each a float.
   */
  public fun uniforms(atlas: LutAtlas): Map<String, FloatArray> =
    mapOf(
      "uLutSize" to floatArrayOf(atlas.size.toFloat()),
      "uLutColumns" to floatArrayOf(atlas.columns.toFloat()),
    )

  private fun fragmentSource(): String =
    """
    #version 300 es
    precision highp float;
    precision highp int;
    precision highp sampler2D;
    in vec2 vUv;
    out vec4 fragColor;
    uniform sampler2D $TEXTURE;
    uniform sampler2D $TABLE;
    uniform float uLutSize, uLutColumns;

    vec3 entry(int red, int green, int blue) {
      int size = int(uLutSize);
      int columns = int(uLutColumns);
      ivec2 at = ivec2((blue % columns) * size + red, (blue / columns) * size + green);
      vec3 high = texelFetch($TABLE, at, 0).rgb;
      vec3 low = texelFetch($TABLE, at + ivec2(columns * size, 0), 0).rgb;
      return (round(high * ${BYTE_STEPS}.0) * ${BYTE_RANGE}.0 + round(low * ${BYTE_STEPS}.0)) / $MAX_ENTRY_GLSL;
    }

    vec3 throughTable(vec3 codes) {
      float last = uLutSize - 1.0;
      vec3 position = clamp(codes, 0.0, 1.0) * last;
      vec3 corner = min(floor(position), vec3(last - 1.0));
      vec3 part = position - corner;
      int r = int(corner.r);
      int g = int(corner.g);
      int b = int(corner.b);
      vec3 near = mix(
        mix(entry(r, g, b), entry(r + 1, g, b), part.r),
        mix(entry(r, g + 1, b), entry(r + 1, g + 1, b), part.r),
        part.g
      );
      vec3 far = mix(
        mix(entry(r, g, b + 1), entry(r + 1, g, b + 1), part.r),
        mix(entry(r, g + 1, b + 1), entry(r + 1, g + 1, b + 1), part.r),
        part.g
      );
      return mix(near, far, part.b);
    }

    void main() {
      vec4 texel = textureLod($TEXTURE, vUv, 0.0);
      fragColor = vec4(throughTable(texel.rgb), texel.a);
    }
    """.trimIndent()
}

private const val BYTE_STEPS = 255
private const val BYTE_RANGE = 256
private const val MAX_ENTRY_GLSL = "65535.0"
