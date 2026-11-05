package dev.jordond.filmstrip.webcodecs.internal

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
