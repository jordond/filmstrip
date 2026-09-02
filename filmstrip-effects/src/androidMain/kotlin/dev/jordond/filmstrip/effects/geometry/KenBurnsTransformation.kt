package dev.jordond.filmstrip.effects.geometry

import android.graphics.Matrix
import androidx.media3.effect.MatrixTransformation
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.NormalizedRect
import kotlin.time.Duration.Companion.microseconds

/**
 * Draws a pan as a per-frame vertex transform.
 *
 * media3 hands a matrix transformation the presentation time and takes back the matrix to draw that
 * frame with, which is the seam a pan travels through. The frame keeps the size it arrived at,
 * since the default configure of a matrix transformation passes the input size out unchanged and
 * pixels the transform pushes outside the frame are clipped.
 *
 * The presentation time media3 hands over is already the composition time [span] is expressed in,
 * whether the item's offset came from the sequence it plays in or from the clock a frame reader put
 * in front of this.
 *
 * A fresh matrix leaves each call, since media3 compares what it is given against what it last
 * uploaded and a mutated instance reads as unchanged.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal class KenBurnsTransformation(
  private val spec: KenBurns,
  private val span: TimeRange,
) : MatrixTransformation {
  override fun getMatrix(presentationTimeUs: Long): Matrix =
    spec.regionAt(presentationTimeUs.microseconds, span).toMatrix()
}

// filmstrip measures `0..1` from the top-left with +Y down. A vertex shader measures `[-1, 1]` from
// the centre with +Y up, so the region's centre moves to the origin and both sides open out by the
// reciprocal of the region's own.
private fun NormalizedRect.toMatrix(): Matrix {
  val scaleX = 1f / width
  val scaleY = 1f / height
  val centreX = (2f * (left + right) / 2f) - 1f
  val centreY = 1f - (2f * (top + bottom) / 2f)

  return Matrix().apply {
    setScale(scaleX, scaleY)
    postTranslate(-centreX * scaleX, -centreY * scaleY)
  }
}
