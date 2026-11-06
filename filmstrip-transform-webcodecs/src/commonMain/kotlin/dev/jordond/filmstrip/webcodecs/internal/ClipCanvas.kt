package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.transform.internal.geometry.CanvasPlacement

/**
 * One canvas a letterboxing or stretching crop builds for the effects after it to read, bars included.
 *
 * The compositor builds every canvas a clip reads inside the composite pass rather than in a framebuffer of its own. A
 * point the clip's [RenderedClip.matrix] lands on its last canvas is either inside [content], where it is taken through
 * [contentMatrix] onto the canvas before, or the source for the first, or on a bar, where it takes [bars]. That is the
 * canvas every other backend draws, with the content at [CanvasPlacement.contentInCanvas].
 *
 * @property contentMatrix Takes a point of the canvas to the canvas before it, or to the source for the first, through
 * the content's place on the canvas and then every effect after that canvas up to and including this one's crop.
 * @property content Where the content sits on the canvas as GL texture coordinates, measured from the bottom left:
 * left, bottom, right and top.
 * @property bars What a bar holds, as RGBA. Clear, so the fill shows through, or opaque black under [Fill.Blurred],
 * where it is part of the picture the cover is blurred from.
 */
internal class ClipCanvas(
  val contentMatrix: TextureMatrix,
  val content: FloatArray,
  val bars: FloatArray,
) {
  /**
   * This canvas with clear bars, the way a [ClipPassStage] draws it so nothing that runs over it can grade a bar.
   */
  fun withClearBars(): ClipCanvas = ClipCanvas(contentMatrix, content, CLEAR_BARS)

  companion object {
    /**
     * The canvas [canvas] describes, where [toCrop] is the texture matrix of every effect after the canvas before it up
     * to and including the crop that builds this one, and [fill] is the composition's.
     */
    fun of(
      toCrop: TextureMatrix,
      canvas: CanvasPlacement,
      fill: Fill,
    ): ClipCanvas {
      val rect = canvas.contentInCanvas
      val left = rect.left
      val bottom = 1f - rect.bottom
      // Column-major, the third column carrying the offset, so a canvas point inside the rect lands on 0 to 1.
      val canvasToContent =
        floatArrayOf(
          1f / rect.width,
          0f,
          0f,
          0f,
          1f / rect.height,
          0f,
          -left / rect.width,
          -bottom / rect.height,
          1f,
        )
      return ClipCanvas(
        contentMatrix = toCrop.then(canvasToContent),
        content = floatArrayOf(left, bottom, rect.right, 1f - rect.top),
        bars = if (fill is Fill.Blurred) BLACK_BARS else CLEAR_BARS,
      )
    }

    private val CLEAR_BARS = floatArrayOf(0f, 0f, 0f, 0f)
    private val BLACK_BARS = floatArrayOf(0f, 0f, 0f, 1f)
  }
}

/**
 * What a clip with a shader pass draws ahead of the composite: its chain so far on a frame of its own, clear where a
 * bar or an edge leaves nothing, and then each pass over that frame. A clip's grade on a canvas under [Fill.Blurred]
 * takes the same route, so it never reaches a bar.
 *
 * The composite then lays the result in the clip's quad through whatever runs after the clip, with the clip's own color
 * and canvases already in it.
 *
 * @property matrix The texture matrix of the clip's chain up to the passes, reading its last canvas when it builds any.
 * @property colorMatrix The clip's own color chain, as [RenderedClip.colorMatrix] holds one.
 * @property canvases Every canvas the clip's chain builds, as [RenderedClip.canvases] holds them.
 * @property width The frame's width in pixels, the clip's quad on the output frame.
 * @property height The same, for height.
 * @property passes The clip's shader passes, in chain order, with [BARS_OVER_BLACK] last under [Fill.Blurred].
 */
internal class ClipPassStage(
  val matrix: TextureMatrix,
  val colorMatrix: FloatArray,
  val canvases: List<ClipCanvas>,
  val width: Int,
  val height: Int,
  val passes: List<WebGlPass>,
)

/**
 * The last pass of a [ClipPassStage] under [Fill.Blurred], which lays the frame over opaque black.
 *
 * A canvas's bars are clear while the clip's effects run. Under a blurred fill they then turn black, part of the
 * picture the cover is blurred from, and picture an effect moved into a bar stays over that black.
 */
internal val BARS_OVER_BLACK: WebGlPass =
  WebGlPass(
    "filmstrip.barsOverBlack",
    listOf(
      WebGlStep(
        name = "bars",
        fragmentSource =
          """
          #version 300 es
          precision highp float;
          uniform sampler2D uFrame;
          in vec2 vUv;
          out vec4 fragColor;
          void main() {
            vec4 texel = texture(uFrame, vUv);
            fragColor = vec4(texel.rgb * texel.a, 1.0);
          }
          """.trimIndent(),
        reads = mapOf("uFrame" to WebGlStep.INPUT),
      ) { _, _, _ -> emptyMap() },
    ),
  )
