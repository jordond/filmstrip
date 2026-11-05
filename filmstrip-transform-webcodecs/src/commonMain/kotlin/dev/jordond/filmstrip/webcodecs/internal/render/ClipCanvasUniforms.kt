@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.webcodecs.internal.ClipCanvas
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration

/**
 * How many canvases the composite pass builds for one clip at most.
 *
 * The shader holds them in fixed arrays, so a clip that builds more is refused before it is drawn.
 */
internal const val MAX_CANVASES: Int = 4

/**
 * The composite program's canvas uniforms, which tell it how many [ClipCanvas] the clip it draws reads, and where each
 * holds content and where bars.
 *
 * A clip with no canvas reads its source straight through.
 */
internal class ClipCanvasUniforms private constructor(
  private val contentMatrices: List<JsAny?>,
  private val contents: List<JsAny?>,
  private val bars: JsAny?,
  private val count: JsAny?,
) {
  /**
   * Uploads [canvases] for the clip about to be drawn. A content matrix that moves with time is left for [uploadAt].
   */
  fun upload(
    gl: WebGl2,
    canvases: List<ClipCanvas>,
  ) {
    gl.uniform1i(count, canvases.size)
    canvases.forEachIndexed { index, canvas ->
      val (left, bottom, right, top) = canvas.content
      gl.uniform4f(contents[index], left, bottom, right, top)
      val (red, green, blue, alpha) = canvas.bars
      gl.uniform4f(bars, red, green, blue, alpha)
    }
    uploadAt(gl, canvases, Duration.ZERO, moving = false)
  }

  /**
   * Uploads the content matrix of every canvas in [canvases] on the frame drawn at [compositionTime], only those that
   * move with time when [moving] is true and only those that do not otherwise.
   */
  fun uploadAt(
    gl: WebGl2,
    canvases: List<ClipCanvas>,
    compositionTime: Duration,
    moving: Boolean = true,
  ) {
    canvases.forEachIndexed { index, canvas ->
      if (canvas.contentMatrix.varies != moving) return@forEachIndexed
      gl.uniformMatrix3fv(contentMatrices[index], false, canvas.contentMatrix.at(compositionTime).toFloat32Array())
    }
  }

  companion object {
    /**
     * Looks the uniforms up on the linked composite [program].
     */
    fun of(
      gl: WebGl2,
      program: JsAny,
    ): ClipCanvasUniforms =
      ClipCanvasUniforms(
        contentMatrices = (0 until MAX_CANVASES).map { gl.getUniformLocation(program, "uContentMatrix[$it]") },
        contents = (0 until MAX_CANVASES).map { gl.getUniformLocation(program, "uContent[$it]") },
        bars = gl.getUniformLocation(program, "uBars"),
        count = gl.getUniformLocation(program, "uCanvasCount"),
      )
  }
}
