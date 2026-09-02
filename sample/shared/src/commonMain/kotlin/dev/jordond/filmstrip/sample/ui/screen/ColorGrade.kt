@file:OptIn(InternalFilmstripApi::class)

package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.fusedColorMatrices
import dev.jordond.filmstrip.effects.color.then
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

/**
 * Draws the content through the colour grade [effects] author, folded through the same fold the
 * backends lower, so the schematic shows the grade the export will write. Anything in [effects]
 * that is not a colour matrix is skipped, and an identity grade draws the content as it is.
 */
internal fun Modifier.colorGraded(effects: List<EffectSpec>): Modifier {
  val matrix =
    effects.fusedColorMatrices().fold(ColorMatrix.Identity) { folded, entry ->
      colorMatrixOf(entry.spec)?.let(folded::then) ?: folded
    }
  if (matrix == ColorMatrix.Identity) return this

  return drawWithCache {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(matrix.toComposeColorMatrix()) }
    onDrawWithContent {
      val canvas = drawContext.canvas
      canvas.saveLayer(size.toRect(), paint)
      drawContent()
      canvas.restore()
    }
  }
}

// Compose lays a colour matrix out as four rows of five, with alpha as the fourth input and the
// bias in the fifth column on a scale of 0 to 255.
private fun ColorMatrix.toComposeColorMatrix(): ComposeColorMatrix =
  ComposeColorMatrix(
    floatArrayOf(
      rr, rg, rb, 0f, rBias * CODE_RANGE,
      gr, gg, gb, 0f, gBias * CODE_RANGE,
      br, bg, bb, 0f, bBias * CODE_RANGE,
      0f, 0f, 0f, 1f, 0f,
    ),
  )

private const val CODE_RANGE = 255f
