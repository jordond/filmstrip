package dev.jordond.filmstrip.media3.internal

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.util.Size
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import kotlin.concurrent.Volatile

/**
 * One position in a lowered effect chain, and what may replace what sits there.
 *
 * media3 builds its shader programs once per graph, so an effect object cannot be exchanged for
 * another after the graph is standing. What it does re-read every draw is the matrix an
 * [RgbMatrix] or a [MatrixTransformation] hands back, which is the seam a parameter change travels
 * through. Anything else in the chain is fixed for the life of the graph and only matches a
 * re-lowering that produced the same value.
 */
internal sealed interface LiveSlot {
  /**
   * What goes into the chain at this position.
   */
  val effect: Effect

  /**
   * Whether [next] can take this position without the graph being rebuilt.
   */
  fun accepts(next: Effect): Boolean

  /**
   * Puts [next] behind this position, for every frame drawn from here on.
   */
  fun install(next: Effect)
}

/**
 * A colour matrix that can be exchanged while the graph runs.
 *
 * `DefaultShaderProgram` asks every [RgbMatrix] in its group for a matrix on each draw and compares
 * the answer against the one it last uploaded, so a swap reaches the next frame, including the one
 * `experimentalRedrawLastFrame` replays at the presentation time already drawn.
 */
internal class LiveRgbMatrix(
  initial: RgbMatrix,
) : RgbMatrix,
  LiveSlot {
  @Volatile
  private var delegate: RgbMatrix = initial

  override val effect: Effect get() = this

  override fun getMatrix(
    presentationTimeUs: Long,
    useHdr: Boolean,
  ): FloatArray = delegate.getMatrix(presentationTimeUs, useHdr)

  // Never a no-op, whatever the matrix currently says. media3 drops a no-op effect while it builds
  // the chain, and a dropped position cannot take a later parameter.
  override fun isNoOp(
    inputWidth: Int,
    inputHeight: Int,
  ): Boolean = false

  override fun accepts(next: Effect): Boolean = next is RgbMatrix

  override fun install(next: Effect) {
    delegate = next as RgbMatrix
  }
}

/**
 * A geometry matrix that can be exchanged while the graph runs.
 *
 * The frame this position outputs is settled once, when media3 configures the shader program, so a
 * replacement that would size the frame differently is refused and the caller rebuilds instead. The
 * matrix itself is read per draw and swaps freely.
 */
internal class LiveMatrixTransformation(
  initial: MatrixTransformation,
) : MatrixTransformation,
  LiveSlot {
  @Volatile
  private var delegate: MatrixTransformation = initial

  @Volatile
  private var configured: Configured? = null

  override val effect: Effect get() = this

  override fun getMatrix(presentationTimeUs: Long): Matrix = delegate.getMatrix(presentationTimeUs)

  override fun getGlTextureMinFilter(): Int = delegate.glTextureMinFilter

  override fun configure(
    inputWidth: Int,
    inputHeight: Int,
  ): Size {
    val output = delegate.configure(inputWidth, inputHeight)
    configured = Configured(inputWidth, inputHeight, output)
    return output
  }

  // See LiveRgbMatrix. A crop that currently spans the whole frame still holds its position.
  override fun isNoOp(
    inputWidth: Int,
    inputHeight: Int,
  ): Boolean = false

  override fun accepts(next: Effect): Boolean {
    if (next !is MatrixTransformation) return false
    val standing = configured ?: return true
    val output = next.configure(standing.inputWidth, standing.inputHeight)
    return output.width == standing.output.width && output.height == standing.output.height
  }

  override fun install(next: Effect) {
    delegate = next as MatrixTransformation
  }

  private class Configured(
    val inputWidth: Int,
    val inputHeight: Int,
    val output: Size,
  )
}

/**
 * A position nothing can be swapped into, matched by value against a re-lowering.
 *
 * Overlays and the fill passes land here. An overlay owns a texture media3 uploads and frees on its
 * own render thread, so exchanging one under a running graph is media3's to allow and it does not.
 */
internal class FixedSlot(
  override val effect: Effect,
) : LiveSlot {
  override fun accepts(next: Effect): Boolean = next == effect

  override fun install(next: Effect): Unit = Unit
}

/**
 * The slot [effect] belongs in.
 */
internal fun liveSlotFor(effect: Effect): LiveSlot =
  when (effect) {
    is RgbMatrix -> LiveRgbMatrix(effect)
    is MatrixTransformation -> LiveMatrixTransformation(effect)
    else -> FixedSlot(effect)
  }
