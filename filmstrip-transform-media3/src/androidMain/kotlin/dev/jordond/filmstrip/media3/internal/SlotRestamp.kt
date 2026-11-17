package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import kotlin.math.abs

/**
 * Moves the frame stamped within [toleranceUs] of [fromUs] to [toUs] and hands every other frame on as it is.
 *
 * A frame reader answers a slot a held sample shows with that sample, stamped where the clip writes it, which is the
 * first slot the sample shows. Moving it onto the slot being read makes the effects after it draw that slot, as an
 * export does. A second one with the two times swapped puts the frame back on the time the reader looks it up by.
 *
 * @property fromUs The stamp of the frame to move, in microseconds.
 * @property toUs The stamp the frame leaves with, in microseconds.
 * @property toleranceUs How far a stamp may sit from [fromUs] and still be that frame, which covers a stamp rounded to
 * another whole microsecond.
 */
internal data class SlotRestamp(
  val fromUs: Long,
  val toUs: Long,
  val toleranceUs: Long = STAMP_TOLERANCE_US,
) : GlEffect {
  /**
   * The effect that undoes this one.
   */
  val inverse: SlotRestamp get() = SlotRestamp(toUs, fromUs, toleranceUs)

  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = RestampProgram(this)
}

private class RestampProgram(
  private val restamp: SlotRestamp,
) : PassthroughShaderProgram() {
  override fun queueInputFrame(
    glObjectsProvider: GlObjectsProvider,
    inputTexture: GlTextureInfo,
    presentationTimeUs: Long,
  ) {
    super.queueInputFrame(
      glObjectsProvider,
      inputTexture,
      if (abs(presentationTimeUs - restamp.fromUs) <= restamp.toleranceUs) restamp.toUs else presentationTimeUs,
    )
  }
}

private const val STAMP_TOLERANCE_US = 2L
