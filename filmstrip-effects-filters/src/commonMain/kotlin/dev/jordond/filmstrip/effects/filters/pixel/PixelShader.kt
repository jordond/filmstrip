package dev.jordond.filmstrip.effects.filters.pixel

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.glslFloat

/**
 * The GLSL ES 3.00 fragment source that draws a [Pixel], read by media3 and the browser.
 *
 * Cells, levels and dither are uniforms from [uniforms], and a palette is written into the source by [fragment], one
 * comparison an entry, so each palette is its own program. Every position is worked out in integers the way
 * [PixelSignal] does, with rows counted from the top. It reads at the `vUv` varying, and takes [FLIP_V] as 1 when `v`
 * runs up the picture.
 *
 * The colour is cut on the straight codes the cell read and takes that read's alpha. A clear bar stays clear, and a
 * cell that lands on one goes clear with it.
 */
@InternalFilmstripApi
public object PixelShader {
  /**
   * The sampler the frame is bound to.
   */
  public const val TEXTURE: String = "uTexture"

  /**
   * The uniform set to 1 when `v` runs up the picture, and 0 when it runs down.
   */
  public const val FLIP_V: String = "uFlipV"

  /**
   * The fragment source for [palette], or for levels alone when it is null.
   */
  public fun fragment(palette: PixelPalette?): String =
    if (palette == null) LEVELS_FRAGMENT else fragmentSource(PixelSignal.codesOf(palette))

  /**
   * The uniforms for [pixel] on a frame [width] by [height], by name, each a float or a vec by its length.
   *
   * It holds only the names the [fragment] for [pixel] reads, so a palette's map has no levels and a levels map has no
   * spread. [FLIP_V] and [TEXTURE] belong to whoever runs the pass.
   */
  public fun uniforms(
    pixel: Pixel,
    width: Int,
    height: Int,
  ): Map<String, FloatArray> {
    val palette = pixel.palette
    val grid =
      mapOf(
        SIZE to floatArrayOf(width.toFloat(), height.toFloat()),
        GRID to floatArrayOf(PixelSignal.columns(width, height, pixel.cells).toFloat(), pixel.cells.toFloat()),
        DITHER to floatArrayOf(pixel.dither),
      )
    return if (palette == null) {
      grid + (LEVELS to floatArrayOf(pixel.levels.toFloat()))
    } else {
      grid + (SPREAD to floatArrayOf(PixelSignal.paletteSpread(palette.colors.size).toFloat()))
    }
  }

  private val LEVELS_FRAGMENT: String by lazy { fragmentSource(null) }
}

private const val SIZE = "uSize"
private const val GRID = "uGrid"
private const val DITHER = "uDither"
private const val LEVELS = "uLevels"
private const val SPREAD = "uSpread"

private fun fragmentSource(palette: List<DoubleArray>?): String =
  header(if (palette == null) LEVELS else SPREAD) + "\n" + colourFunction(palette) + "\n" + MAIN

// Each variant declares only the uniforms it reads, the same names PixelShader.uniforms hands it.
private fun header(cut: String): String =
  """
  #version 300 es
  precision highp float;
  precision highp int;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D ${PixelShader.TEXTURE};
  uniform float ${PixelShader.FLIP_V};
  uniform vec2 $SIZE, $GRID;
  uniform float $DITHER, $cut;
  """.trimIndent()

private val MAIN: String
  get() =
    """
    const float BAYER[16] = float[16](${bayerTable()});

    int fromTop(int row, int height) {
      return ${PixelShader.FLIP_V} > 0.5 ? height - 1 - row : row;
    }

    void main() {
      ivec2 size = ivec2($SIZE);
      ivec2 grid = ivec2($GRID);
      ivec2 texel = clamp(ivec2(floor(vUv * $SIZE)), ivec2(0), size - 1);
      ivec2 pixel = ivec2(texel.x, fromTop(texel.y, size.y));
      ivec2 cell = ((2 * pixel + 1) * grid) / (2 * size);
      ivec2 centre = ((2 * cell + 1) * size) / (2 * grid);
      vec4 read = texelFetch(${PixelShader.TEXTURE}, ivec2(centre.x, fromTop(centre.y, size.y)), 0);
      float threshold = BAYER[(cell.y % $BAYER_SIZE) * $BAYER_SIZE + cell.x % $BAYER_SIZE];
      fragColor = vec4(colourOf(read.rgb, threshold), read.a);
    }
    """.trimIndent()

// The palette search unrolled with a strict less than, so the first entry wins a tie as PixelSignal.nearest does.
private fun colourFunction(palette: List<DoubleArray>?): String =
  if (palette == null) {
    """
    vec3 colourOf(vec3 rgb, float threshold) {
      if ($LEVELS < 0.5) return rgb;
      float steps = $LEVELS - 1.0;
      return floor(rgb * steps + $DITHER * threshold + 0.5) / steps;
    }
    """.trimIndent()
  } else {
    val entries = palette.map { "vec3(${glslFloat(it[0])}, ${glslFloat(it[1])}, ${glslFloat(it[2])})" }
    buildString {
      appendLine("vec3 colourOf(vec3 rgb, float threshold) {")
      appendLine("  vec3 moved = rgb + $DITHER * threshold * $SPREAD;")
      appendLine("  vec3 best = ${entries.first()};")
      appendLine("  vec3 gap = moved - best;")
      appendLine("  float bestDistance = dot(gap, gap);")
      entries.drop(1).forEach { entry ->
        appendLine("  gap = moved - $entry;")
        appendLine("  if (dot(gap, gap) < bestDistance) { bestDistance = dot(gap, gap); best = $entry; }")
      }
      appendLine("  return best;")
      append("}")
    }
  }

private fun bayerTable(): String =
  (0 until BAYER_SIZE * BAYER_SIZE).joinToString(", ") { index ->
    glslFloat(PixelSignal.bayer(index % BAYER_SIZE, index / BAYER_SIZE))
  }
