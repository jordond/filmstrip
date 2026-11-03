package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.util.Size
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import dev.jordond.filmstrip.effects.color.HdrColorMatrixEffect
import dev.jordond.filmstrip.effects.color.HdrColorMatrixShaderProgram
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
   * The swap that puts [next] behind this position, for every frame drawn from here on once it is installed, or null
   * when [next] cannot take this position without the graph being rebuilt.
   *
   * Nothing moves until the swap is installed, so a caller can check every position before swapping any.
   */
  fun swapTo(next: Effect): SlotSwap?
}

/**
 * A replacement a [LiveSlot] has taken, waiting to be put behind its position.
 */
internal fun interface SlotSwap {
  /**
   * Puts the replacement behind the position, for every frame drawn from here on.
   */
  fun install()
}

/**
 * A color matrix that can be exchanged while the graph runs.
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

  override fun swapTo(next: Effect): SlotSwap? = (next as? RgbMatrix)?.let { matrix -> SlotSwap { delegate = matrix } }
}

/**
 * A color matrix on a kept HDR grade that can be exchanged while the graph runs.
 *
 * The pass reads its matrix through this slot on every draw, so a swap reaches the next frame the
 * same way an [RgbMatrix] swap does. The transfer function is settled with the graph, and a
 * re-lowering that changed it is refused here as well as by the preview.
 */
internal class LiveHdrColorMatrix(
  initial: HdrColorMatrixEffect,
) : GlEffect,
  LiveSlot {
  @Volatile
  private var delegate: HdrColorMatrixEffect = initial

  /**
   * The matrix the next draw reads.
   */
  val current: HdrColorMatrixEffect get() = delegate

  override val effect: Effect get() = this

  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = HdrColorMatrixShaderProgram(useHdr) { delegate }

  // See LiveRgbMatrix. An identity matrix still holds its position.
  override fun isNoOp(
    inputWidth: Int,
    inputHeight: Int,
  ): Boolean = false

  override fun swapTo(next: Effect): SlotSwap? {
    if (next !is HdrColorMatrixEffect || next.transfer != delegate.transfer) return null
    return SlotSwap { delegate = next }
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

  override fun swapTo(next: Effect): SlotSwap? {
    if (next !is MatrixTransformation) return null
    val standing = configured
    if (standing != null) {
      val output = next.configure(standing.inputWidth, standing.inputHeight)
      if (output.width != standing.output.width || output.height != standing.output.height) return null
    }
    return SlotSwap { delegate = next }
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
  override fun swapTo(next: Effect): SlotSwap? = if (next == effect) SlotSwap {} else null
}

/**
 * The slot [effect] belongs in.
 */
internal fun liveSlotFor(effect: Effect): LiveSlot =
  when (effect) {
    is RgbMatrix -> LiveRgbMatrix(effect)
    is HdrColorMatrixEffect -> LiveHdrColorMatrix(effect)
    is MatrixTransformation -> LiveMatrixTransformation(effect)
    else -> FixedSlot(effect)
  }
